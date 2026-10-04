package de.chennemann.agentic.domain.usage

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.AndroidClientMetadata
import de.chennemann.agentic.data.t3.EnvironmentAuthClient
import de.chennemann.agentic.data.t3.UsageRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.AuthSession
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.ServerConfigStreamEvent
import de.chennemann.agentic.t3.contract.TokenExchangeResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsageServiceTest {
    private val environment = SavedEnvironment("env", "Server", "http://localhost", "linux", "x64", "1", false, null)
    private val environments = object : EnvironmentRepository {
        override val environments = MutableStateFlow(listOf(environment))
        override val activeEnvironment = MutableStateFlow<SavedEnvironment?>(environment)
        override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) =
            Unit
        override suspend fun select(environmentId: String) = Unit
        override suspend fun remove(environmentId: String) = Unit
        override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
    }
    private val credentials = object : CredentialStore {
        override suspend fun read(environmentId: String) = "test-token"
        override suspend fun write(environmentId: String, bearerToken: String) = Unit
        override suspend fun remove(environmentId: String) = Unit
    }
    private val auth = object : EnvironmentAuthClient {
        override suspend fun exchangeToken(
            baseUrl: String,
            bootstrapCredential: String,
            metadata: AndroidClientMetadata
        ): TokenExchangeResponse = error("Not used")
        override suspend fun session(baseUrl: String, bearerToken: String): AuthSession = error("Not used")
    }

    @Test fun `redemption sends pinned hub credit once then refreshes quotas even for already redeemed`() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val rpc = object : UsageRpcClient {
            override fun usageConfigStream(baseUrl: String, bearerToken: String) = emptyFlow<ServerConfigStreamEvent>()
            override suspend fun usageCall(
                baseUrl: String,
                bearerToken: String,
                method: String,
                input: JsonObject
            ): JsonElement {
                calls += method to input
                return if (method ==
                    "provider.consumeResetCredit"
                ) {
                    buildJsonObject {
                        put("outcome", "alreadyRedeemed")
                        put("warning", "Cooldown unchanged")
                    }
                } else {
                    JsonObject(emptyMap())
                }
            }
        }
        val result = UsageService(
            environments,
            credentials,
            rpc,
            auth,
            UsageRepository()
        ).redeem(ResetTarget("env", sourceId = "hub", accountId = "account", creditId = "credit-1"))
        assertEquals("alreadyRedeemed", result.outcome)
        assertEquals("Cooldown unchanged", result.warning)
        assertEquals(listOf("provider.consumeResetCredit", "server.refreshProviders"), calls.map { it.first })
        assertEquals("credit-1", calls.first().second["creditId"]?.jsonPrimitive?.content)
    }

    @Test fun `simultaneous reset submissions cannot consume two credits`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val rpc = object : UsageRpcClient {
            override fun usageConfigStream(baseUrl: String, bearerToken: String) = emptyFlow<ServerConfigStreamEvent>()
            override suspend fun usageCall(
                baseUrl: String,
                bearerToken: String,
                method: String,
                input: JsonObject
            ): JsonElement {
                if (method ==
                    "provider.consumeResetCredit"
                ) {
                    calls++
                    entered.complete(Unit)
                    release.await()
                    return buildJsonObject { put("outcome", "reset") }
                }
                return JsonObject(emptyMap())
            }
        }
        val service = UsageService(environments, credentials, rpc, auth, UsageRepository())
        val target = ResetTarget("env", instanceId = "provider")
        val first = async { service.redeem(target) }
        entered.await()
        val second = runCatching { service.redeem(target) }
        assertTrue(second.exceptionOrNull() is IllegalStateException)
        release.complete(Unit)
        first.await()
        assertEquals(1, calls)
    }
}
