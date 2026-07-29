package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.AuthSession
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.EnvironmentClientConfig
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.TokenExchangeResponse
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

interface EnvironmentConfigClient {
    suspend fun clientConfig(
        baseUrl: String,
        bearerToken: String,
    ): EnvironmentClientConfig
}

interface OrchestrationSnapshotClient {
    suspend fun shellSnapshot(
        baseUrl: String,
        bearerToken: String,
    ): OrchestrationShellSnapshot

    suspend fun threadSnapshot(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
    ): OrchestrationThreadDetailSnapshot
}

interface OrchestrationCommandClient {
    suspend fun dispatch(
        baseUrl: String,
        bearerToken: String,
        command: ClientOrchestrationCommand,
    ): DispatchResult
}

interface OrchestrationStreamClient {
    fun shellStream(
        baseUrl: String,
        bearerToken: String,
        afterSequence: Long,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationShellStreamItem>

    fun threadStream(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        afterSequence: Long,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationThreadStreamItem>
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

    class InvalidResponse : T3TransportException("T3 returned an invalid portable-protocol response.")

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
