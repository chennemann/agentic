package de.chennemann.agentic.domain.guestshare

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.AndroidClientMetadata
import de.chennemann.agentic.data.t3.AttachmentAssetClient
import de.chennemann.agentic.data.t3.EnvironmentAuthClient
import de.chennemann.agentic.data.t3.GuestShareRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.t3.contract.AuthSession
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ServerAuthDescriptor
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.TokenExchangeResponse
import de.chennemann.agentic.t3.contract.V2Orchestration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A guest share as the server encodes it on the thread projection, with one waiting message. */
internal const val GuestShareJson = """{"id":"share-1","host":"abc.t3.example","createdAt":"2026-10-19T10:00:00.000Z",
    "expiresAt":"2026-10-20T10:00:00.000Z","guestName":"Alex","messages":[
    {"id":"g-1","text":"Look at main","status":"pending","submittedAt":"2026-10-19T10:05:00.000Z",
     "attachments":[{"type":"image","id":"att-1","name":"shot.png","mimeType":"image/png","sizeBytes":2048}]},
    {"id":"g-0","text":"Hi","status":"dismissed","submittedAt":"2026-10-19T10:01:00.000Z"}]}"""

internal class GuestShareFixture {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var settings = """{"guestSharingEnabled":true}"""
    var permissions = listOf("orchestration:operate", "access:write", "settings:write")

    /** When set, dispatches suspend until it completes, so in-flight behavior can be observed. */
    var dispatchGate: CompletableDeferred<Unit>? = null
    val repository = FakeOrchestrationRepository()

    private val environment = SavedEnvironment("env", "Server", "https://example.test", "linux", "x64", "1", true, null)
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
    private val assets = object : AttachmentAssetClient {
        override suspend fun loadDataUrl(baseUrl: String, bearerToken: String, attachmentId: String, mimeType: String) =
            "data:$mimeType;base64,AAAA"
    }
    private val rpc = object : GuestShareRpcClient {
        override suspend fun guestShareCall(baseUrl: String, bearerToken: String, method: String, input: JsonObject): JsonElement {
            calls += method to input
            return when (method) {
                "server.getSettings" -> T3Json.parseToJsonElement(settings)
                "guestShare.create" -> T3Json.parseToJsonElement(
                    """{"url":"https://abc.t3.example/guest#share-1.sig","share":$GuestShareJson}"""
                )
                else -> {
                    dispatchGate?.await()
                    T3Json.parseToJsonElement("""{"sequence":7}""")
                }
            }
        }
    }
    private val auth = object : EnvironmentAuthClient {
        override suspend fun exchangeToken(
            baseUrl: String,
            bootstrapCredential: String,
            metadata: AndroidClientMetadata
        ): TokenExchangeResponse = error("Not used")
        override suspend fun session(baseUrl: String, bearerToken: String) = AuthSession(
            authenticated = true,
            auth = ServerAuthDescriptor("required", emptyList(), emptyList(), "session"),
            scopes = permissions
        )
    }
    private var nextId = 0
    val service = GuestShareService(environments, credentials, auth, rpc, assets, repository) { "command-${++nextId}" }

    fun focus(share: String?) {
        repository.focusedThread.value = ProjectionState(snapshot(""","guestShare":${share ?: "null"}"""))
    }

    private fun snapshot(extra: String): OrchestrationThreadDetailSnapshot = V2Orchestration.snapshot(
        T3Json.parseToJsonElement(
            """{"snapshotSequence":4,"projection":{"thread":{"id":"thread-1","projectId":"project-1","title":"Shared",
            "modelSelection":{"instanceId":"provider","model":"model"},"runtimeMode":"full-access","interactionMode":"default",
            "createdAt":"2026-10-19T10:00:00Z","updatedAt":"2026-10-19T10:00:00Z"$extra},"updatedAt":"2026-10-19T10:00:00Z"}}"""
        ).jsonObject
    )
}

internal class FakeOrchestrationRepository : OrchestrationRepository {
    override val clientConfig = MutableStateFlow(ProjectionState<ServerConfig>())
    override val shell = MutableStateFlow(ProjectionState<OrchestrationShellSnapshot>())
    override val focusedThread = MutableStateFlow(ProjectionState<OrchestrationThreadDetailSnapshot>())
    override val focusedThreadId = MutableStateFlow<String?>(null)
    override val selectedProjectId = MutableStateFlow<String?>(null)
    override suspend fun loadCached(environmentId: String) = Unit
    override suspend fun setClientConfig(environmentId: String, config: ServerConfig, source: ProjectionSource) = Unit
    override suspend fun applyShellItem(
        environmentId: String,
        item: OrchestrationShellStreamItem
    ): Reduction<ProjectionState<OrchestrationShellSnapshot>> = Reduction.Ignored(shell.value)
    override suspend fun focusThread(environmentId: String, threadId: String?) = Unit
    override suspend fun selectProject(environmentId: String, projectId: String?) = Unit
    override suspend fun applyThreadItem(
        environmentId: String,
        threadId: String,
        item: OrchestrationThreadStreamItem
    ): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>> = Reduction.Ignored(focusedThread.value)
    override suspend fun clearEnvironment(environmentId: String) = Unit
}
