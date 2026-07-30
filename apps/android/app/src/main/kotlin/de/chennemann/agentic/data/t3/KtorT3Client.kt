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
import de.chennemann.agentic.t3.contract.PortableCommandJson
import de.chennemann.agentic.t3.contract.PortableJson
import de.chennemann.agentic.t3.contract.TokenExchangeResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

class KtorT3Client(
    private val httpClient: HttpClient,
) : EnvironmentMetadataClient,
    EnvironmentAuthClient,
    EnvironmentConfigClient,
    OrchestrationSnapshotClient,
    OrchestrationCommandClient,
    OrchestrationStreamClient {
    override suspend fun environmentDescriptor(baseUrl: String): ExecutionEnvironmentDescriptor =
        getJson(url(baseUrl, "/.well-known/t3/environment"))

    override suspend fun exchangeToken(
        baseUrl: String,
        bootstrapCredential: String,
        metadata: AndroidClientMetadata,
    ): TokenExchangeResponse = transport {
        val response = httpClient.submitForm(
            url = url(baseUrl, "/oauth/token"),
            formParameters = Parameters.build {
                append("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                append("subject_token", bootstrapCredential)
                append("subject_token_type", "urn:t3:params:oauth:token-type:environment-bootstrap")
                append("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
                append("scope", REQUIRED_T3_SCOPES)
                append("client_label", metadata.label)
                append("client_device_type", metadata.deviceType)
                append("client_os", metadata.os)
            },
        )
        response.checked()
        response.body()
    }

    override suspend fun session(
        baseUrl: String,
        bearerToken: String,
    ): AuthSession = getJson(url(baseUrl, "/api/auth/session"), bearerToken)

    override suspend fun clientConfig(
        baseUrl: String,
        bearerToken: String,
    ): EnvironmentClientConfig = getJson(url(baseUrl, "/api/environment/client-config"), bearerToken)

    override suspend fun shellSnapshot(
        baseUrl: String,
        bearerToken: String,
    ): OrchestrationShellSnapshot = getJson(url(baseUrl, "/api/orchestration/shell"), bearerToken)

    override suspend fun threadSnapshot(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
    ): OrchestrationThreadDetailSnapshot = getJson(
        url(baseUrl, "/api/orchestration/threads/${threadId.encodeURLPathPart()}"),
        bearerToken,
    )

    override suspend fun dispatch(
        baseUrl: String,
        bearerToken: String,
        command: ClientOrchestrationCommand,
    ): DispatchResult = transport {
        val response = httpClient.post(url(baseUrl, "/api/orchestration/dispatch")) {
            bearerAuth(bearerToken)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(PortableCommandJson.encodeToString(ClientOrchestrationCommand.serializer(), command))
        }
        response.checked()
        PortableJson.decodeFromString(response.body<String>())
    }

    override fun shellStream(
        baseUrl: String,
        bearerToken: String,
        afterSequence: Long,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationShellStreamItem> = stream(
        url = url(baseUrl, "/api/orchestration/shell/stream"),
        bearerToken = bearerToken,
        afterSequence = afterSequence,
        requestCompletionMarker = requestCompletionMarker,
        decode = PortableJson::decodeFromString,
    )

    override fun threadStream(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        afterSequence: Long,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationThreadStreamItem> = stream(
        url = url(baseUrl, "/api/orchestration/threads/${threadId.encodeURLPathPart()}/stream"),
        bearerToken = bearerToken,
        afterSequence = afterSequence,
        requestCompletionMarker = requestCompletionMarker,
        decode = PortableJson::decodeFromString,
    )

    private suspend inline fun <reified T> getJson(
        url: String,
        bearerToken: String? = null,
    ): T = transport {
        val response = httpClient.get(url) {
            if (bearerToken != null) bearerAuth(bearerToken)
            accept(ContentType.Application.Json)
        }
        response.checked()
        response.body()
    }

    private fun <T> stream(
        url: String,
        bearerToken: String,
        afterSequence: Long,
        requestCompletionMarker: Boolean,
        decode: (String) -> T,
    ): Flow<T> = flow {
        transport {
            httpClient.prepareGet(url) {
                bearerAuth(bearerToken)
                accept(ContentType.Text.EventStream)
                parameter("afterSequence", afterSequence)
                parameter("requestCompletionMarker", requestCompletionMarker)
            }.execute { response ->
                response.checked()
                val channel = response.bodyAsChannel()
                val parser = SseParser()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    parser.feedLine(line)?.let { emit(decode(it.data)) }
                }
                parser.finish()?.let { emit(decode(it.data)) }
            }
        }
    }

    private fun HttpResponse.checked() {
        if (status.isSuccess()) return
        if (status.value == 401 || status.value == 403) {
            throw T3TransportException.Authentication(status.value)
        }
        throw T3TransportException.Http(status.value)
    }

    private suspend fun <T> transport(block: suspend () -> T): T = try {
        block()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: T3TransportException) {
        throw cause
    } catch (cause: SerializationException) {
        throw T3TransportException.InvalidResponse()
    } catch (cause: JsonConvertException) {
        throw T3TransportException.InvalidResponse()
    } catch (_: Exception) {
        throw T3TransportException.Network()
    }

    private fun url(
        baseUrl: String,
        path: String,
    ): String = baseUrl.trimEnd('/') + path
}
