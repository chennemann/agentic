package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.AuthSession
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.FilesystemBrowseResult
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.ServerConfigStreamEvent
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.ProjectCreateNewResult
import de.chennemann.agentic.t3.contract.ProjectEnsureScratchResult
import de.chennemann.agentic.t3.contract.TokenExchangeResponse
import de.chennemann.agentic.t3.contract.TerminalAttachEvent
import de.chennemann.agentic.t3.contract.TerminalMetadataEvent
import de.chennemann.agentic.t3.contract.TerminalSessionSnapshot
import de.chennemann.agentic.t3.contract.VcsListRefsResult
import de.chennemann.agentic.t3.contract.WorkspaceEntriesResult
import de.chennemann.agentic.t3.contract.WorkspaceFileResult
import kotlinx.coroutines.flow.Flow

// Conversation commands, terminal observation/control, workspace files/media, usage, and usage settings.
const val REQUIRED_T3_SCOPES =
    "orchestration:read orchestration:operate terminal:read terminal:operate " +
        "filesystem:read diagnostics:read settings:write providers:manage source-control:write"

data class AndroidClientMetadata(
    val label: String,
    val os: String,
    val deviceType: String = "mobile",
)

data class ClientActivityReport(
    val environmentId: String,
    val clientId: String,
    val visible: Boolean,
    val appState: String,
    val threadIds: Set<String>,
    val observedAt: String,
)

interface EnvironmentMetadataClient {
    suspend fun environmentDescriptor(baseUrl: String): ExecutionEnvironmentDescriptor
}

interface EnvironmentAuthClient {
    suspend fun exchangeToken(
        baseUrl: String,
        bootstrapCredential: String,
        metadata: AndroidClientMetadata,
    ): TokenExchangeResponse

    suspend fun session(
        baseUrl: String,
        bearerToken: String,
    ): AuthSession
}

interface T3RpcClient {
    suspend fun serverConfig(
        baseUrl: String,
        bearerToken: String,
    ): ServerConfig

    suspend fun dispatch(
        baseUrl: String,
        bearerToken: String,
        command: ClientOrchestrationCommand,
    ): DispatchResult

    fun configStream(baseUrl: String, bearerToken: String): Flow<ServerConfigStreamEvent> =
        kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }

    suspend fun threadHistory(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        beforeCursor: String?,
        projection: de.chennemann.agentic.t3.contract.V2Projection? = null,
    ): OrchestrationThreadDetailSnapshot = error("Thread history is not supported.")

    suspend fun ensureScratch(baseUrl: String, bearerToken: String): ProjectEnsureScratchResult =
        error("Scratch projects are not supported.")

    suspend fun createNewProject(baseUrl: String, bearerToken: String, name: String): ProjectCreateNewResult =
        error("New projects are not supported.")

    suspend fun reportClientActivity(
        baseUrl: String,
        bearerToken: String,
        report: ClientActivityReport,
    ): Unit = throw UnsupportedOperationException("Client activity reporting is not supported.")

    fun shellStream(
        baseUrl: String,
        bearerToken: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationShellStreamItem>

    fun threadStream(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationThreadStreamItem>
}

interface ProjectDestinationRpcClient {
    suspend fun browseFilesystem(
        baseUrl: String,
        bearerToken: String,
        partialPath: String,
    ): FilesystemBrowseResult
}

interface ThreadWorkspaceRpcClient {
    suspend fun listRefs(
        baseUrl: String,
        bearerToken: String,
        cwd: String,
    ): VcsListRefsResult
}

interface WorkspaceFilesRpcClient {
    suspend fun listEntries(baseUrl: String, bearerToken: String, cwd: String): WorkspaceEntriesResult
    suspend fun readFile(baseUrl: String, bearerToken: String, cwd: String, relativePath: String): WorkspaceFileResult
    suspend fun loadWorkspaceImage(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        absolutePath: String,
        mimeType: String,
    ): String

    /** A media file anywhere the host can read; relative paths resolve against the thread workspace. */
    suspend fun loadMediaFile(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        path: String,
        mimeType: String,
    ): String
}

interface TerminalRpcClient {
    suspend fun resizeTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String, cols: Int, rows: Int)
    fun terminalMetadata(baseUrl: String, bearerToken: String): Flow<TerminalMetadataEvent>
    fun attachTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String): Flow<TerminalAttachEvent>
    suspend fun openTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String, cwd: String, worktreePath: String?, env: Map<String, String>): TerminalSessionSnapshot
    suspend fun writeTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String, data: String)
    suspend fun clearTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String)
    suspend fun restartTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String, cwd: String, worktreePath: String?, env: Map<String, String>): TerminalSessionSnapshot
    suspend fun closeTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String)
}

interface AttachmentAssetClient {
    suspend fun loadDataUrl(baseUrl: String, bearerToken: String, attachmentId: String, mimeType: String): String
    suspend fun loadHtml(baseUrl: String, bearerToken: String, attachmentId: String): String =
        error("HTML pages are not supported.")
    suspend fun loadGalleryImage(source: String): String = error("Gallery images are not supported.")
}

sealed class T3TransportException(
    message: String,
) : Exception(message) {
    class Authentication(
        val statusCode: Int,
    ) : T3TransportException("T3 authentication is required.")

    class Authorization(
        val requiredScope: String,
    ) : T3TransportException(
        if (requiredScope == "terminal:operate") {
            "Terminal access requires pairing this environment again."
        } else {
            "T3 authorization is missing the required permission: $requiredScope. Create a new pairing link with this permission and pair this environment again."
        },
    )

    class Http(
        val statusCode: Int,
    ) : T3TransportException("T3 request failed with HTTP $statusCode.")

    class ClientCertificate(
        val required: Boolean,
    ) : T3TransportException(
        if (required) {
            "The server requires a device certificate. Select the installed client certificate for this server."
        } else {
            "The server rejected the device certificate. Check the selected certificate and the proxy's trusted CA and certificate revocation list."
        },
    )

    class InvalidResponse : T3TransportException("T3 returned an invalid RPC response.")

    class UnsupportedStreamItem(
        val kind: String,
    ) : T3TransportException("T3 returned an unsupported stream item kind: $kind.")

    class Rpc(
        message: String,
        val traceId: String? = null,
    ) : T3TransportException(message)

    class Network(
        val reason: NetworkFailure = NetworkFailure.UNREACHABLE,
    ) : T3TransportException(reason.message)

    /** The host stopped answering on an open connection, so the network path was already working. */
    class Unresponsive(
        val silentSeconds: Int,
    ) : T3TransportException("The T3 environment stopped responding for ${silentSeconds}s.")

    /** The server rejected this client's orchestration protocol (HTTP 426). */
    class IncompatibleProtocol(
        val serverProtocolVersion: Int? = null,
    ) : T3TransportException("The T3 server uses an incompatible orchestration protocol.")
}

/** Why a request never got an answer; only failures before a socket opened can blame the network path. */
enum class NetworkFailure(val message: String) {
    UNREACHABLE("The T3 environment could not be reached."),
    UNRESOLVED_HOST("The T3 environment's address could not be resolved."),
    REFUSED("Nothing accepted the connection at the T3 environment's address."),
    TIMEOUT("The T3 environment did not answer in time."),
    TLS("A secure connection to the T3 environment could not be established."),
    DISCONNECTED("The T3 environment closed the connection."),
}

fun networkFailureOf(cause: Throwable): NetworkFailure {
    var current: Throwable? = cause
    while (current != null) {
        when (current) {
            is java.net.UnknownHostException -> return NetworkFailure.UNRESOLVED_HOST
            is java.net.ConnectException -> return NetworkFailure.REFUSED
            is java.net.SocketTimeoutException -> return NetworkFailure.TIMEOUT
            is javax.net.ssl.SSLException -> return NetworkFailure.TLS
        }
        if (current.javaClass.simpleName.contains("Timeout")) return NetworkFailure.TIMEOUT
        current = current.cause?.takeIf { it !== current }
    }
    return NetworkFailure.UNREACHABLE
}

fun redactTransportText(
    value: String,
    secrets: Collection<String> = emptyList(),
): String {
    var redacted = value
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+"), "$1[redacted]")
        .replace(Regex("(?i)(subject_token|access_token|token)=([^\\s&#,;]+)"), "$1=[redacted]")
    secrets.filter(String::isNotBlank).forEach { redacted = redacted.replace(it, "[redacted]") }
    return redacted.take(512)
}
