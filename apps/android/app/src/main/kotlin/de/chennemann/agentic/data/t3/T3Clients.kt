package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.AuthSession
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.FilesystemBrowseResult
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.TokenExchangeResponse
import de.chennemann.agentic.t3.contract.VcsListRefsResult
import de.chennemann.agentic.t3.contract.WorkspaceEntriesResult
import kotlinx.coroutines.flow.Flow

const val REQUIRED_T3_SCOPES = "orchestration:read orchestration:operate"

data class AndroidClientMetadata(
    val label: String,
    val os: String,
    val deviceType: String = "mobile",
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
}

interface AttachmentAssetClient {
    suspend fun loadDataUrl(baseUrl: String, bearerToken: String, attachmentId: String, mimeType: String): String
}

sealed class T3TransportException(
    message: String,
) : Exception(message) {
    class Authentication(
        val statusCode: Int,
    ) : T3TransportException("T3 authentication is required.")

    class Http(
        val statusCode: Int,
    ) : T3TransportException("T3 request failed with HTTP $statusCode.")

    class InvalidResponse : T3TransportException("T3 returned an invalid RPC response.")

    class Rpc(
        message: String,
    ) : T3TransportException(message)

    class Network : T3TransportException("The T3 environment could not be reached.")
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
