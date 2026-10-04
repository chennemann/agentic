package de.chennemann.agentic.domain.usage

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.UsageRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.ClientConfigReducer
import de.chennemann.agentic.t3.contract.ConsumeResetResult
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.ServerSettings
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.UsageModelPrice
import de.chennemann.agentic.t3.contract.UsageSummary
import de.chennemann.agentic.t3.contract.UsageSummaryInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class EnvironmentUsage(
    val environment: SavedEnvironment,
    val summary: UsageSummary? = null,
    val config: ServerConfig? = null,
    val settings: ServerSettings? = null,
    val canOperate: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null
)

class UsageRepository {
    private val mutable = MutableStateFlow<Map<String, EnvironmentUsage>>(emptyMap())
    val state = mutable.asStateFlow()
    fun retain(ids: Set<String>) {
        mutable.update { it.filterKeys(ids::contains) }
    }
    fun update(environment: SavedEnvironment, transform: (EnvironmentUsage) -> EnvironmentUsage) {
        mutable.update { it + (environment.id to transform(it[environment.id] ?: EnvironmentUsage(environment))) }
    }
}

data class ResetTarget(
    val environmentId: String,
    val instanceId: String? = null,
    val sourceId: String? = null,
    val accountId: String? = null,
    val creditId: String? = null
) {
    fun input(): JsonObject = buildJsonObject {
        if (instanceId != null) {
            put("instanceId", instanceId)
        } else {
            put("sourceId", requireNotNull(sourceId))
            put("accountId", requireNotNull(accountId))
            put("creditId", requireNotNull(creditId))
        }
    }
}

class UsageService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val rpc: UsageRpcClient,
    private val auth: de.chennemann.agentic.data.t3.EnvironmentAuthClient,
    val repository: UsageRepository
) {
    private val redemption = Mutex()

    suspend fun observe(): Unit = coroutineScope {
        environments.environments.map { list ->
            list.map { it.copy(active = false, lastConnectedAt = null) }
        }.distinctUntilChanged().collectLatest { list ->
            repository.retain(list.map { it.id }.toSet())
            coroutineScope {
                list.forEach { environment ->
                    launch {
                        while (true) {
                            try {
                                val token = token(environment)
                                val session = auth.session(environment.baseUrl, token)
                                repository.update(environment) {
                                    it.copy(
                                        canOperate =
                                        session.authenticated && "orchestration:operate" in session.scopes
                                    )
                                }
                                rpc.usageConfigStream(environment.baseUrl, token).collect { event ->
                                    repository.update(environment) { current ->
                                        val config =
                                            event.config
                                                ?: current.config?.let { ClientConfigReducer.applyEvent(it, event) }
                                        current.copy(
                                            config = config,
                                            settings = config?.settings ?: current.settings,
                                            connected = true,
                                            error = null
                                        )
                                    }
                                }
                                error("Usage connection closed.")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                repository.update(environment) {
                                    it.copy(connected = false, error = "Usage connection unavailable. Reconnecting…")
                                }
                                delay(5000)
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun refresh(ids: Set<String>?, input: UsageSummaryInput, refreshRates: Boolean = false) = coroutineScope {
        environments.environments.value.filter { ids == null || it.id in ids }.forEach { environment ->
            launch {
                try {
                    if (refreshRates) rpcCall(environment, "server.refreshUsageRates", buildJsonObject {})
                    val summary = T3Json.decodeFromJsonElement<UsageSummary>(
                        rpcCall(
                            environment,
                            "server.getUsageSummary",
                            T3Json.encodeToJsonElement(UsageSummaryInput.serializer(), input).jsonObject
                        )
                    )
                    repository.update(environment) { it.copy(summary = summary, error = null) }
                    val settings = T3Json.decodeFromJsonElement<ServerSettings>(
                        rpcCall(
                            environment,
                            "server.getSettings",
                            buildJsonObject {
                            }
                        )
                    )
                    repository.update(environment) { it.copy(settings = settings) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    _: Exception
                ) {
                    repository.update(environment) {
                        it.copy(error = "Could not refresh usage. Retry when this environment is connected.")
                    }
                }
            }
        }
    }

    suspend fun refreshLimits(ids: Set<String>?) = coroutineScope {
        environments.environments.value.filter { ids == null || it.id in ids }.forEach { environment ->
            launch {
                try {
                    rpcCall(environment, "server.refreshProviders", buildJsonObject {})
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    _: Exception
                ) {
                    repository.update(environment) { it.copy(error = "Could not refresh subscription limits.") }
                }
            }
        }
    }

    suspend fun redeem(target: ResetTarget): ConsumeResetResult {
        check(redemption.tryLock()) { "A reset is already being redeemed." }
        try {
            val environment = environment(target.environmentId)
            val result = T3Json.decodeFromJsonElement<ConsumeResetResult>(
                rpcCall(environment, "provider.consumeResetCredit", target.input())
            )
            refreshLimits(setOf(environment.id))
            return result
        } finally {
            redemption.unlock()
        }
    }

    suspend fun savePrice(ids: Set<String>, model: String, price: UsageModelPrice?): Map<String, Boolean> {
        require(model.isNotBlank())
        val results = mutableMapOf<String, Boolean>()
        for (id in ids) {
            try {
                val environment = environment(id)
                val current = repository.state.value[id]
                check(
                    current?.canOperate == true &&
                        current.connected &&
                        current.config?.environment?.capabilities?.usagePriceOverrides == true
                ) {
                    "Price editing unavailable."
                }
                val settings = T3Json.decodeFromJsonElement<ServerSettings>(
                    rpcCall(
                        environment,
                        "server.updateSettings",
                        buildJsonObject {
                            put(
                                "patch",
                                buildJsonObject {
                                    put(
                                        "usagePriceOverrides",
                                        buildJsonObject {
                                            put(
                                                model.trim(),
                                                price?.let {
                                                    T3Json.encodeToJsonElement(UsageModelPrice.serializer(), it)
                                                }
                                                    ?: JsonNull
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                )
                repository.update(environment) { it.copy(settings = settings) }
                results[id] = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                results[id] = false
            }
        }
        return results
    }

    suspend fun enableKeychain(id: String) {
        val environment = environment(id)
        check(repository.state.value[id]?.canOperate == true)
        rpcCall(
            environment,
            "server.updateSettings",
            buildJsonObject {
                put("patch", buildJsonObject { put("cursorKeychainUsageEnabled", true) })
            }
        )
        refreshLimits(setOf(id))
    }

    private fun environment(id: String) = requireNotNull(
        environments.environments.value.firstOrNull {
            it.id == id
        }
    ) { "Environment removed." }
    private suspend fun token(environment: SavedEnvironment) =
        requireNotNull(credentials.read(environment.id)) { "Pair this environment again." }
    private suspend fun rpcCall(environment: SavedEnvironment, method: String, input: JsonObject) =
        rpc.usageCall(environment.baseUrl, token(environment), method, input)
}
