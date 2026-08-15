package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.AuthSession
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.FilesystemBrowseResult
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.T3CommandJson
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.TokenExchangeResponse
import de.chennemann.agentic.t3.contract.VcsListRefsResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class KtorT3Client(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
) : EnvironmentMetadataClient,
    EnvironmentAuthClient,
    T3RpcClient,
    ProjectDestinationRpcClient,
    ThreadWorkspaceRpcClient,
    AttachmentAssetClient {
    private val connectionMutex = Mutex()
    private val connections = mutableMapOf<ConnectionKey, EffectRpcConnection>()

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

    override suspend fun serverConfig(
        baseUrl: String,
        bearerToken: String,
    ): ServerConfig = rpcCall(baseUrl, bearerToken, "server.getConfig", buildJsonObject {})

    override suspend fun dispatch(
        baseUrl: String,
        bearerToken: String,
        command: ClientOrchestrationCommand,
    ): DispatchResult = rpcCall(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        tag = "orchestration.dispatchCommand",
        payload = T3CommandJson.encodeToJsonElement(ClientOrchestrationCommand.serializer(), command),
    )

    override suspend fun browseFilesystem(
        baseUrl: String,
        bearerToken: String,
        partialPath: String,
    ): FilesystemBrowseResult = rpcCall(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        tag = "filesystem.browse",
        payload = buildJsonObject { put("partialPath", partialPath) },
    )

    override suspend fun listRefs(
        baseUrl: String,
        bearerToken: String,
        cwd: String,
    ): VcsListRefsResult = rpcCall(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        tag = "vcs.listRefs",
        payload = buildJsonObject {
            put("cwd", cwd)
            put("includeMatchingRemoteRefs", true)
            put("limit", 100)
        },
    )

    override suspend fun loadDataUrl(
        baseUrl: String,
        bearerToken: String,
        attachmentId: String,
        mimeType: String,
    ): String {
        val asset: de.chennemann.agentic.t3.contract.AssetCreateUrlResult = rpcCall(
            baseUrl,
            bearerToken,
            "assets.createUrl",
            buildJsonObject {
                put("resource", buildJsonObject {
                    put("_tag", "attachment")
                    put("attachmentId", attachmentId)
                })
            },
        )
        val bytes = httpClient.get(url(baseUrl, asset.relativeUrl)).body<ByteArray>()
        return "data:$mimeType;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
    }

    override fun shellStream(
        baseUrl: String,
        bearerToken: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationShellStreamItem> = rpcStream(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        tag = "orchestration.subscribeShell",
        payload = subscriptionPayload(afterSequence, requestCompletionMarker),
    )

    override fun threadStream(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationThreadStreamItem> = rpcStream(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        tag = "orchestration.subscribeThread",
        payload = subscriptionPayload(afterSequence, requestCompletionMarker, threadId),
    )

    private suspend inline fun <reified T> rpcCall(
        baseUrl: String,
        bearerToken: String,
        tag: String,
        payload: JsonElement,
    ): T = transport {
        T3Json.decodeFromJsonElement(connection(baseUrl, bearerToken).call(tag, payload))
    }

    private inline fun <reified T> rpcStream(
        baseUrl: String,
        bearerToken: String,
        tag: String,
        payload: JsonElement,
    ): Flow<T> = flow {
        transport {
            connection(baseUrl, bearerToken).stream(tag, payload).collect {
                emit(T3Json.decodeFromJsonElement(it))
            }
        }
    }

    private suspend fun connection(
        baseUrl: String,
        bearerToken: String,
    ): EffectRpcConnection = connectionMutex.withLock {
        val key = ConnectionKey(baseUrl.trimEnd('/'), bearerToken)
        connections[key]?.takeIf(EffectRpcConnection::isActive)?.let { return@withLock it }

        val ticketResponse = httpClient.post(url(key.baseUrl, "/api/auth/websocket-ticket")) {
            bearerAuth(bearerToken)
            accept(ContentType.Application.Json)
        }
        ticketResponse.checked()
        val ticket = T3Json.parseToJsonElement(ticketResponse.body<String>())
            .jsonObject
            .getValue("ticket")
            .jsonPrimitive
            .content
        val session = httpClient.webSocketSession(urlString = webSocketUrl(key.baseUrl, ticket))
        lateinit var created: EffectRpcConnection
        created = EffectRpcConnection(session, scope) {
            scope.launch {
                connectionMutex.withLock {
                    if (connections[key] === created) connections.remove(key)
                }
            }
        }
        connections[key] = created
        created
    }

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

    private fun subscriptionPayload(
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
        threadId: String? = null,
    ): JsonObject = buildJsonObject {
        if (afterSequence != null) put("afterSequence", afterSequence)
        put("requestCompletionMarker", requestCompletionMarker)
        if (threadId != null) put("threadId", threadId)
    }

    private fun webSocketUrl(baseUrl: String, ticket: String): String {
        val source = URI(baseUrl.trimEnd('/'))
        val scheme = when (source.scheme?.lowercase()) {
            "http" -> "ws"
            "https" -> "wss"
            else -> throw IllegalArgumentException("Unsupported T3 environment URL scheme")
        }
        val path = source.rawPath.orEmpty().trimEnd('/') + "/ws"
        val encodedTicket = URLEncoder.encode(ticket, StandardCharsets.UTF_8).replace("+", "%20")
        return "$scheme://${source.rawAuthority}$path?wsTicket=$encodedTicket"
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

    private data class ConnectionKey(
        val baseUrl: String,
        val bearerToken: String,
    )
}

private class EffectRpcConnection(
    private val session: DefaultClientWebSocketSession,
    scope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    private val nextRequestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, Channel<JsonObject>>()
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var heartbeat: Job? = null
    private val reader = scope.launch { readIncoming() }

    init {
        heartbeat = scope.launch {
            while (!closed.get()) {
                delay(HeartbeatMillis)
                send(buildJsonObject { put("_tag", "Ping") })
            }
        }
    }

    fun isActive(): Boolean = !closed.get() && reader.isActive

    suspend fun call(
        tag: String,
        payload: JsonElement,
    ): JsonElement {
        val request = startRequest(tag, payload)
        var completed = false
        try {
            for (message in request.responses) {
                if (message.tag() != "Exit") continue
                completed = true
                return exitValue(message)
            }
            throw T3TransportException.Network()
        } finally {
            finish(request, interrupt = !completed)
        }
    }

    fun stream(
        tag: String,
        payload: JsonElement,
    ): Flow<JsonElement> = flow {
        val request = startRequest(tag, payload)
        var completed = false
        try {
            for (message in request.responses) {
                when (message.tag()) {
                    "Chunk" -> message["values"]?.jsonArray?.forEach { emit(it) }
                    "Exit" -> {
                        completed = true
                        exitValue(message)
                        return@flow
                    }
                }
            }
            throw T3TransportException.Network()
        } finally {
            finish(request, interrupt = !completed)
        }
    }

    private suspend fun startRequest(
        tag: String,
        payload: JsonElement,
    ): PendingRequest {
        if (!isActive()) throw T3TransportException.Network()
        val id = nextRequestId.getAndIncrement().toString()
        val responses = Channel<JsonObject>(Channel.UNLIMITED)
        pending[id] = responses
        try {
            send(
                buildJsonObject {
                    put("_tag", "Request")
                    put("id", id)
                    put("tag", tag)
                    put("payload", payload)
                    put("headers", buildJsonArray {})
                },
            )
        } catch (cause: Exception) {
            pending.remove(id)
            responses.close(cause)
            throw cause
        }
        return PendingRequest(id, responses)
    }

    private suspend fun finish(
        request: PendingRequest,
        interrupt: Boolean,
    ) {
        pending.remove(request.id)
        request.responses.close()
        if (interrupt && isActive()) {
            runCatching {
                send(
                    buildJsonObject {
                        put("_tag", "Interrupt")
                        put("requestId", request.id)
                    },
                )
            }
        }
    }

    private suspend fun readIncoming() {
        var failure: Throwable = T3TransportException.Network()
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val message = T3Json.parseToJsonElement(frame.readText()).jsonObject
                when (message.tag()) {
                    "Pong" -> Unit
                    "ClientProtocolError", "Defect" -> throw T3TransportException.InvalidResponse()
                    else -> {
                        val requestId = message["requestId"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (message.tag() == "Chunk") acknowledge(requestId)
                        pending[requestId]?.send(message)
                    }
                }
            }
        } catch (cause: CancellationException) {
            failure = cause
            throw cause
        } catch (cause: Throwable) {
            failure = cause
        } finally {
            closed.set(true)
            heartbeat?.cancel()
            pending.values.forEach { it.close(failure) }
            pending.clear()
            runCatching { session.close() }
            onClosed()
        }
    }

    private suspend fun acknowledge(requestId: String) {
        send(
            buildJsonObject {
                put("_tag", "Ack")
                put("requestId", requestId)
            },
        )
    }

    private suspend fun send(message: JsonObject) {
        writeMutex.withLock {
            session.send(Frame.Text(message.toString()))
        }
    }

    private fun exitValue(message: JsonObject): JsonElement {
        val exit = message["exit"]?.jsonObject ?: throw T3TransportException.InvalidResponse()
        return when (exit.tag()) {
            "Success" -> exit["value"] ?: JsonNull
            "Failure" -> throw rpcFailure(exit["cause"] as? JsonArray)
            else -> throw T3TransportException.InvalidResponse()
        }
    }

    private fun rpcFailure(cause: JsonArray?): T3TransportException {
        val error = cause
            ?.firstOrNull { it.jsonObject["_tag"]?.jsonPrimitive?.contentOrNull == "Fail" }
            ?.jsonObject
            ?.get("error")
            ?.jsonObject
        if (error?.get("_tag")?.jsonPrimitive?.contentOrNull == "EnvironmentAuthorizationError") {
            return T3TransportException.Authentication(403)
        }
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?.let(::redactTransportText)
            ?: "T3 RPC request failed."
        return T3TransportException.Rpc(message)
    }

    private fun JsonObject.tag(): String? = get("_tag")?.jsonPrimitive?.contentOrNull

    private data class PendingRequest(
        val id: String,
        val responses: Channel<JsonObject>,
    )

    private companion object {
        const val HeartbeatMillis = 10_000L
    }
}
