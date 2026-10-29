package de.chennemann.agentic.domain.settings

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.EnvironmentAuthClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Generic settings RPC transport: `server.getConfig` and `server.updateSettings`. */
fun interface ServerSettingsRpcClient {
    suspend fun settingsCall(baseUrl: String, bearerToken: String, method: String, input: JsonObject): JsonElement
}

/**
 * The environment's server settings as the server encodes them. Settings stay raw so per-project override sets
 * round-trip keys this client does not model when one of them is replaced.
 */
data class ServerSettingsSnapshot(
    val environmentId: String,
    val environmentLabel: String,
    val settings: JsonObject,
    val canWrite: Boolean
)

data class ServerSettingsState(
    val snapshot: ServerSettingsSnapshot? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null
)

class ServerSettingsRepository {
    private val mutable = MutableStateFlow(ServerSettingsState())
    val state: StateFlow<ServerSettingsState> = mutable.asStateFlow()

    fun update(transform: (ServerSettingsState) -> ServerSettingsState) = mutable.update(transform)
}

class ServerSettingsService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val rpc: ServerSettingsRpcClient,
    private val auth: EnvironmentAuthClient,
    val repository: ServerSettingsRepository
) {
    private val writes = Mutex()

    suspend fun load() {
        val environment = environments.activeEnvironment.value
        if (environment == null) {
            repository.update { ServerSettingsState(error = "Select an environment to edit its server settings.") }
            return
        }
        repository.update {
            it.copy(
                snapshot = it.snapshot?.takeIf { snapshot -> snapshot.environmentId == environment.id },
                loading = true,
                error = null
            )
        }
        try {
            val token = token(environment)
            val canWrite = runCatching { auth.session(environment.baseUrl, token).grants(SettingsWritePermission) }
                .getOrElse { if (it is CancellationException) throw it else false }
            val settings = rpc.settingsCall(environment.baseUrl, token, "server.getConfig", buildJsonObject {}).jsonObject.getValue("settings")
            repository.update {
                it.copy(
                    snapshot = ServerSettingsSnapshot(environment.id, environment.label, settings as JsonObject, canWrite),
                    loading = false
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            repository.update { it.copy(loading = false, error = "Could not load server settings. Check the connection and try again.") }
        }
    }

    /** Sets an environment-wide setting. */
    suspend fun updateGlobal(key: String, value: JsonElement) = write { globalSettingPatch(key, value) }

    /** Sets a project's own value for [key], or inherits the environment value again when [value] is null. */
    suspend fun updateProject(projectId: String, key: String, value: JsonElement?) =
        write { settings -> projectOverridePatch(settings, projectId, key, value) }

    private suspend fun write(patch: (JsonObject) -> JsonObject) = writes.withLock {
        val snapshot = repository.state.value.snapshot ?: return@withLock false
        val environment = environments.activeEnvironment.value?.takeIf { it.id == snapshot.environmentId }
            ?: return@withLock false
        if (!snapshot.canWrite) {
            repository.update { it.copy(error = "This pairing cannot change server settings.") }
            return@withLock false
        }
        repository.update { it.copy(saving = true, error = null) }
        try {
            val settings = rpc.settingsCall(
                environment.baseUrl,
                token(environment),
                "server.updateSettings",
                patch(snapshot.settings)
            ) as JsonObject
            repository.update { it.copy(snapshot = snapshot.copy(settings = settings), saving = false) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            repository.update { it.copy(saving = false, error = "Could not save the setting. Try again.") }
            false
        }
    }

    private suspend fun token(environment: SavedEnvironment) =
        requireNotNull(credentials.read(environment.id)) { "Pair this environment again." }

    private companion object {
        const val SettingsWritePermission = "settings:write"
    }
}
