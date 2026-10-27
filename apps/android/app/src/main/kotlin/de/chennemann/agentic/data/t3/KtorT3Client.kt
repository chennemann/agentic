package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.AuthSession
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.FilesystemBrowseResult
import de.chennemann.agentic.t3.contract.ORCHESTRATION_PROTOCOL_VERSION
import de.chennemann.agentic.t3.contract.OrchestrationProtocolIncompatible
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.ServerConfigStreamEvent
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.ProjectCreateNewResult
import de.chennemann.agentic.t3.contract.ProjectEnsureScratchResult
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.V2Orchestration
import de.chennemann.agentic.t3.contract.V2Commands
import de.chennemann.agentic.t3.contract.V2Projection
import de.chennemann.agentic.t3.contract.V2VisibleItem
import de.chennemann.agentic.t3.contract.V2WireCompaction
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailPage
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
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.serialization.JsonConvertException
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    private val httpClients: T3HttpClientProvider,
    private val scope: CoroutineScope,
) : EnvironmentMetadataClient,
    EnvironmentAuthClient,
    PullRequestsRpcClient,
    T3RpcClient,
    ProjectDestinationRpcClient,
    ThreadWorkspaceRpcClient,
    WorkspaceFilesRpcClient,
    TerminalRpcClient,
    ReviewDiffRpcClient,
    ComposerRpcClient,
    AttachmentAssetClient,
    UsageRpcClient {
    constructor(httpClient: HttpClient, scope: CoroutineScope) : this(
        T3HttpClientProvider { httpClient },
        scope,
    )

    private val connectionMutex = Mutex()
    private val connections = mutableMapOf<ConnectionKey, EffectRpcConnection>()

    override suspend fun environmentDescriptor(baseUrl: String): ExecutionEnvironmentDescriptor =
        getJson(url(baseUrl, "/.well-known/t3/environment"))

    override suspend fun exchangeToken(
        baseUrl: String,
        bootstrapCredential: String,
        metadata: AndroidClientMetadata,
    ): TokenExchangeResponse = transport {
        val response = httpClients.client(baseUrl).submitForm(
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

    override fun configStream(baseUrl: String, bearerToken: String): Flow<ServerConfigStreamEvent> =
        rpcStream(baseUrl, bearerToken, "subscribeServerConfig", buildJsonObject {})

    override suspend fun pullRequestCall(baseUrl: String, token: String, method: String, input: JsonObject): JsonElement =
        rpcCall(baseUrl, token, method, input)

    override fun pullRequestRefreshes(baseUrl: String, token: String): Flow<Long> =
        rpcStream(baseUrl, token, "pullRequests.subscribeRefreshes", buildJsonObject {})

    override suspend fun pullRequestDiff(baseUrl: String, token: String, input: JsonObject): JsonElement = transport {
        val response = httpClients.client(baseUrl).post(url(baseUrl, "/api/pull-requests/diff")) {
            bearerAuth(token)
            header("Content-Type", "application/json")
            setBody(input)
        }
        response.checked()
        T3Json.parseToJsonElement(response.bodyAsText())
    }

    override suspend fun usageCall(baseUrl: String, bearerToken: String, method: String, input: JsonObject): JsonElement =
        rpcCall(baseUrl, bearerToken, method, input)

    override fun usageConfigStream(baseUrl: String, bearerToken: String): Flow<ServerConfigStreamEvent> =
        rpcStream(baseUrl, bearerToken, "subscribeServerConfig", buildJsonObject { put("usageLimitSources", true) })

    override suspend fun ensureScratch(baseUrl: String, bearerToken: String): ProjectEnsureScratchResult =
        rpcCall(baseUrl, bearerToken, "projects.ensureScratch", buildJsonObject {})

    override suspend fun createNewProject(baseUrl: String, bearerToken: String, name: String): ProjectCreateNewResult =
        rpcCall(baseUrl, bearerToken, "projects.createNew", buildJsonObject { put("name", name) })

    override suspend fun threadHistory(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        beforeCursor: String?,
        projection: V2Projection?,
    ): OrchestrationThreadDetailSnapshot = transport {
        val encodedId = URLEncoder.encode(threadId, StandardCharsets.UTF_8).replace("+", "%20")
        val endpoint = if (beforeCursor == null) "bounded" else "history"
        val response = httpClients.client(baseUrl).get(url(baseUrl, "/api/orchestration/threads/$encodedId/$endpoint")) {
            bearerAuth(bearerToken)
            accept(ContentType.Application.Json)
            header("x-t3-orchestration-protocol", ORCHESTRATION_PROTOCOL_VERSION.toString())
            url {
                beforeCursor?.let { parameters.append("cursor", it) }
                if (beforeCursor == null) {
                    parameters.append("compactTurnItems", "1")
                    parameters.append("dedupeBodies", "1")
                }
                parameters.append("compactIds", "1")
            }
        }
        response.checked()
        val wire = T3Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val value = if (beforeCursor == null) wire else V2WireCompaction.expandIds(wire, container = null)
        if (beforeCursor == null) V2Orchestration.snapshot(value) else {
            val loaded = requireNotNull(projection) { "Load the conversation before requesting older history." }
            val rows = T3Json.decodeFromJsonElement<List<V2VisibleItem>>(value.getValue("items"))
            val merged = loaded.copy(visibleTurnItems = (rows + loaded.visibleTurnItems).distinctBy { it.item["id"] })
            val sequence = value.getValue("snapshotSequence").jsonPrimitive.content.toLong()
            OrchestrationThreadDetailSnapshot(sequence, merged.detail(),
                OrchestrationThreadDetailPage(value["nextCursor"]?.jsonPrimitive?.contentOrNull,
                    value.getValue("hasMoreHistory").jsonPrimitive.content.toBoolean(), sequence), merged)
        }
    }

    override suspend fun dispatch(
        baseUrl: String,
        bearerToken: String,
        command: ClientOrchestrationCommand,
    ): DispatchResult {
        val message = if (command is ClientOrchestrationCommand.StartTurn) {
            val ids = mutableMapOf<String, String>()
            val attachments = command.message.attachments.map { attachment ->
                val id = if (attachment.dataUrl.isNotEmpty()) {
                    val bytes = java.util.Base64.getDecoder().decode(attachment.dataUrl.substringAfter(","))
                    uploadFileAttachment(baseUrl, bearerToken, attachment.name, attachment.mimeType, bytes.size.toLong(),
                        ByteArrayContent(bytes, ContentType.parse(attachment.mimeType)), attachment.type)
                } else requireNotNull(attachment.id)
                attachment.id?.let { ids[it] = id }
                buildJsonObject {
                    put("type", attachment.type); put("id", id); put("name", attachment.name)
                    put("mimeType", attachment.mimeType); put("sizeBytes", attachment.sizeBytes)
                    attachment.source?.let { put("source", it) }
                }
            }
            V2Commands.message(command, JsonArray(attachments), ids)
        } else null
        var result = DispatchResult(0)
        for (request in V2Commands.requests(command, message)) {
            val response: JsonElement = rpcCall(baseUrl, bearerToken, request.method, request.payload)
            if (request.method == "orchestration.dispatchCommand") result = T3Json.decodeFromJsonElement(response)
        }
        return result
    }

    override suspend fun reportClientActivity(
        baseUrl: String,
        bearerToken: String,
        report: ClientActivityReport,
    ) {
        rpcCall<JsonElement>(
            baseUrl = baseUrl,
            bearerToken = bearerToken,
            tag = "server.reportClientActivity",
            payload = buildJsonObject {
                put("environmentId", report.environmentId)
                put("clientId", report.clientId)
                put("clientKind", "mobile")
                put("visible", report.visible)
                put("focused", report.visible)
                put("recentlyInteracted", report.visible)
                put("appState", report.appState)
                put("scopes", buildJsonArray {
                    report.threadIds.sorted().forEach { threadId ->
                        add(buildJsonObject {
                            put("type", "thread")
                            put("threadId", threadId)
                        })
                    }
                })
                put("ttlMs", 45_000)
                put("observedAt", report.observedAt)
            },
        )
    }

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

    override suspend fun listEntries(
        baseUrl: String,
        bearerToken: String,
        cwd: String,
    ): de.chennemann.agentic.t3.contract.WorkspaceEntriesResult = rpcCall(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        tag = "projects.listEntries",
        payload = buildJsonObject { put("cwd", cwd) },
    )

    override suspend fun readFile(
        baseUrl: String,
        bearerToken: String,
        cwd: String,
        relativePath: String,
    ): de.chennemann.agentic.t3.contract.WorkspaceFileResult = rpcCall(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        tag = "projects.readFile",
        payload = buildJsonObject {
            put("cwd", cwd)
            put("relativePath", relativePath)
        },
    )

    override suspend fun searchComposerPaths(
        baseUrl: String,
        token: String,
        cwd: String,
        query: String
    ): de.chennemann.agentic.t3.contract.WorkspaceEntriesResult = rpcCall(
        baseUrl,
        token,
        "projects.searchEntries",
        buildJsonObject {
            put("cwd", cwd)
            put("query", query.take(256))
            put("limit", 20)
        }
    )

    override suspend fun composerProviders(
        baseUrl: String,
        token: String,
        instanceId: String,
        cwd: String
    ): de.chennemann.agentic.t3.contract.ProviderUpdatedResult = rpcCall(
        baseUrl,
        token,
        "server.refreshProviders",
        buildJsonObject {
            put("instanceId", instanceId)
            put("cwd", cwd)
        }
    )

    override suspend fun uploadPastedText(baseUrl: String, token: String, name: String, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return uploadFileAttachment(baseUrl, token, name, "text/plain", bytes.size.toLong(), ByteArrayContent(bytes, ContentType.Text.Plain))
    }

    override suspend fun uploadFile(baseUrl: String, token: String, name: String, mimeType: String, file: File): String {
        val size = file.length()
        // Streamed with an exact length: the server rejects a body that differs from the announced size.
        val body = object : OutgoingContent.ReadChannelContent() {
            override val contentLength: Long = size
            override val contentType: ContentType =
                runCatching { ContentType.parse(mimeType) }.getOrDefault(ContentType.Application.OctetStream)

            override fun readFrom(): ByteReadChannel = file.readChannel()
        }
        return uploadFileAttachment(baseUrl, token, name, mimeType, size, body)
    }

    private suspend fun uploadFileAttachment(
        baseUrl: String,
        token: String,
        name: String,
        mimeType: String,
        sizeBytes: Long,
        body: OutgoingContent,
        type: String = "file",
    ): String {
        val upload: de.chennemann.agentic.t3.contract.AttachmentUploadUrl = rpcCall(
            baseUrl,
            token,
            "attachments.createUploadUrl",
            buildJsonObject {
                put("type", type)
                put("name", name)
                put("mimeType", mimeType)
                put("sizeBytes", sizeBytes)
            }
        )
        val relative = URI(upload.relativeUrl)
        require(
            !relative.isAbsolute && relative.rawAuthority == null && relative.path.startsWith("/api/attachments/")
        ) {
            "The environment returned an invalid upload URL."
        }
        val response = httpClients.client(baseUrl).post(url(baseUrl, upload.relativeUrl)) {
            setBody(body)
        }
        response.checked()
        return upload.attachmentId
    }

    override suspend fun turnDiff(baseUrl: String, bearerToken: String, threadId: String, fromTurnCount: Int, toTurnCount: Int): de.chennemann.agentic.t3.contract.ThreadTurnDiff = rpcCall(
        baseUrl, bearerToken, "orchestration.getTurnDiff", buildJsonObject {
            put("threadId", threadId)
            put("fromTurnCount", fromTurnCount)
            put("toTurnCount", toTurnCount)
            put("ignoreWhitespace", false)
        }
    )

    override suspend fun fullThreadDiff(baseUrl: String, bearerToken: String, threadId: String, toTurnCount: Int): de.chennemann.agentic.t3.contract.ThreadTurnDiff = rpcCall(
        baseUrl, bearerToken, "orchestration.getFullThreadDiff", buildJsonObject {
            put("threadId", threadId)
            put("toTurnCount", toTurnCount)
            put("ignoreWhitespace", false)
        }
    )

    override fun terminalMetadata(baseUrl: String, bearerToken: String) =
        rpcStream<de.chennemann.agentic.t3.contract.TerminalMetadataEvent>(baseUrl, bearerToken, "subscribeTerminalMetadata", buildJsonObject {})

    override fun attachTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String) =
        rpcStream<de.chennemann.agentic.t3.contract.TerminalAttachEvent>(
            baseUrl,
            bearerToken,
            "terminal.attach",
            buildJsonObject { put("threadId", threadId); put("terminalId", terminalId) },
        )

    override suspend fun openTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String, cwd: String, worktreePath: String?, env: Map<String, String>) =
        rpcCall<de.chennemann.agentic.t3.contract.TerminalSessionSnapshot>(
            baseUrl, bearerToken, "terminal.open", terminalPayload(threadId, terminalId) {
                put("cwd", cwd)
                worktreePath?.let { put("worktreePath", it) }
                put("env", buildJsonObject { env.forEach { (key, value) -> put(key, value) } })
            },
        )

    override suspend fun writeTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String, data: String) {
        rpcCall<JsonElement>(baseUrl, bearerToken, "terminal.write", terminalPayload(threadId, terminalId) { put("data", data) })
    }

    override suspend fun clearTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String) {
        rpcCall<JsonElement>(baseUrl, bearerToken, "terminal.clear", terminalPayload(threadId, terminalId))
    }

    override suspend fun resizeTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String, cols: Int, rows: Int) {
        require(cols in 1..1000 && rows in 1..500)
        rpcCall<JsonElement>(baseUrl, bearerToken, "terminal.resize", terminalPayload(threadId, terminalId) {
            put("cols", cols)
            put("rows", rows)
        })
    }

    override suspend fun restartTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String, cwd: String, worktreePath: String?, env: Map<String, String>) =
        rpcCall<de.chennemann.agentic.t3.contract.TerminalSessionSnapshot>(
            baseUrl, bearerToken, "terminal.restart", terminalPayload(threadId, terminalId) {
                put("cwd", cwd)
                worktreePath?.let { put("worktreePath", it) }
                put("cols", 80)
                put("rows", 24)
                put("env", buildJsonObject { env.forEach { (key, value) -> put(key, value) } })
            },
        )

    override suspend fun closeTerminal(baseUrl: String, bearerToken: String, threadId: String, terminalId: String) {
        rpcCall<JsonElement>(baseUrl, bearerToken, "terminal.close", terminalPayload(threadId, terminalId))
    }

    override suspend fun loadWorkspaceImage(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        absolutePath: String,
        mimeType: String,
    ): String {
        val asset: de.chennemann.agentic.t3.contract.AssetCreateUrlResult = rpcCall(
            baseUrl,
            bearerToken,
            "assets.createUrl",
            buildJsonObject {
                put("resource", buildJsonObject {
                    put("_tag", "workspace-file")
                    put("threadId", threadId)
                    put("path", absolutePath)
                })
            },
        )
        val response = httpClients.client(baseUrl).get(url(baseUrl, asset.relativeUrl)) { bearerAuth(bearerToken) }
        response.checked()
        val bytes = response.body<ByteArray>()
        return "data:$mimeType;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
    }

    override suspend fun loadMediaFile(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        path: String,
        mimeType: String,
    ): String {
        val asset: de.chennemann.agentic.t3.contract.AssetCreateUrlResult = rpcCall(
            baseUrl,
            bearerToken,
            "assets.createUrl",
            buildJsonObject {
                put("resource", buildJsonObject {
                    put("_tag", "media-file")
                    put("threadId", threadId)
                    put("path", path)
                })
            },
        )
        val response = httpClients.client(baseUrl).get(url(baseUrl, asset.relativeUrl)) { bearerAuth(bearerToken) }
        response.checked()
        val bytes = response.body<ByteArray>()
        return "data:$mimeType;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
    }

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
        val response = httpClients.client(baseUrl).get(url(baseUrl, asset.relativeUrl))
        // An error page must not be cached as the image.
        response.checked()
        val bytes = response.body<ByteArray>()
        return "data:$mimeType;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
    }

    override suspend fun loadHtml(baseUrl: String, bearerToken: String, attachmentId: String): String {
        val asset: de.chennemann.agentic.t3.contract.AssetCreateUrlResult = rpcCall(
            baseUrl, bearerToken, "assets.createUrl", buildJsonObject {
                put("resource", buildJsonObject {
                    put("_tag", "attachment")
                    put("attachmentId", attachmentId)
                    put("mimeType", "text/html")
                    put("disposition", "inline")
                })
            },
        )
        val relative = java.net.URI(asset.relativeUrl)
        require(!relative.isAbsolute && relative.rawAuthority == null && relative.path.startsWith("/api/assets/"))
        val response = httpClients.client(baseUrl).get(url(baseUrl, asset.relativeUrl))
        response.checked()
        return response.body<ByteArray>().toString(Charsets.UTF_8)
    }

    override suspend fun loadGalleryImage(source: String): String {
        val uri = java.net.URI(source)
        require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null && uri.userInfo == null)
        // Gallery URLs are page content. Never send the environment's bearer credential.
        val response = httpClients.client(source).get(source)
        response.checked()
        val mimeType = response.headers["Content-Type"]?.substringBefore(';')?.lowercase()
        require(mimeType in de.chennemann.agentic.domain.attachments.SupportedImageMimeTypes) { "The image format is not supported." }
        val bytes = response.body<ByteArray>()
        require(bytes.size <= de.chennemann.agentic.domain.attachments.MaxImageAttachmentBytes) { "Images must be 10 MiB or smaller." }
        return "data:$mimeType;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"
    }

    override fun shellStream(
        baseUrl: String,
        bearerToken: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationShellStreamItem> = merge(rpcStream<JsonElement>(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        tag = "orchestration.subscribeShell",
        payload = subscriptionPayload(afterSequence, requestCompletionMarker),
        supportedKinds = ShellStreamKinds,
    ).map(V2Orchestration::shellItem), rpcStream<JsonElement>(
        baseUrl, bearerToken, "orchestration.subscribeArchivedShell", buildJsonObject {},
        supportedKinds = setOf("snapshot", "thread.updated", "thread.removed"),
    ).map(V2Orchestration::archiveItem))

    override fun threadStream(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationThreadStreamItem> = rpcStream<JsonElement>(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        tag = "orchestration.subscribeThread",
        payload = buildJsonObject {
            subscriptionPayload(afterSequence, requestCompletionMarker, threadId).forEach { (key, value) -> put(key, value) }
            put("acceptBoundedSnapshot", true)
            put("acceptCompactTurnItems", true)
            put("acceptDedupedBodies", true)
            put("acceptCompactIds", true)
        },
        supportedKinds = ThreadStreamKinds,
    ).map(V2Orchestration::threadItem)

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
        supportedKinds: Set<String>? = null,
    ): Flow<T> = flow {
        transport {
            connection(baseUrl, bearerToken).stream(tag, payload).collect {
                supportedKinds?.let { kinds -> requireSupportedStreamKind(it, kinds) }
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

        val httpClient = httpClients.client(key.baseUrl)
        val ticketResponse = httpClient.post(url(key.baseUrl, "/api/auth/websocket-ticket/rpc")) {
            bearerAuth(bearerToken)
            accept(ContentType.Application.Json)
        }
        ticketResponse.checked()
        val ticket = T3Json.parseToJsonElement(ticketResponse.body<String>())
            .jsonObject
            .getValue("ticket")
            .jsonPrimitive
            .content
        val session = try {
            httpClient.webSocketSession(urlString = webSocketUrl(key.baseUrl, ticket))
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            // The upgrade answers 426 when the server speaks another orchestration protocol.
            if (cause.isUpgradeRequired()) throw T3TransportException.IncompatibleProtocol()
            throw cause
        }
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
        val response = httpClients.client(url).get(url) {
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

    private fun terminalPayload(
        threadId: String,
        terminalId: String,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = buildJsonObject {
        put("threadId", threadId)
        put("terminalId", terminalId)
        content()
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
        return "$scheme://${source.rawAuthority}$path?wsTicket=$encodedTicket" +
            "&orchestrationProtocol=$ORCHESTRATION_PROTOCOL_VERSION"
    }

    private suspend fun HttpResponse.checked() {
        if (status.isSuccess()) return
        if (status.value == 400 && headers["Content-Type"]?.substringBefore(';')?.trim() == "text/html") {
            val body = bodyAsText()
            if ("<title>400 No required SSL certificate was sent</title>" in body) {
                throw T3TransportException.ClientCertificate(required = true)
            }
            if ("<title>400 The SSL certificate error</title>" in body) {
                throw T3TransportException.ClientCertificate(required = false)
            }
        }
        if (status.value == 403) {
            val error = runCatching { T3Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()
            if (error?.get("code")?.jsonPrimitive?.contentOrNull == "insufficient_scope") {
                val permission = (error["requiredPermission"] ?: error["requiredScope"])?.jsonPrimitive?.contentOrNull
                if (permission != null) throw T3TransportException.Authorization(permission)
            }
        }
        if (status.value == 401 || status.value == 403) {
            throw T3TransportException.Authentication(status.value)
        }
        if (status.value == 426) {
            val body = runCatching { T3Json.decodeFromString<OrchestrationProtocolIncompatible>(bodyAsText()) }.getOrNull()
            throw T3TransportException.IncompatibleProtocol(body?.orchestrationProtocolVersion)
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
    } catch (cause: Exception) {
        throw T3TransportException.Network(networkFailureOf(cause))
    }

    private fun url(
        baseUrl: String,
        path: String,
    ): String = baseUrl.trimEnd('/') + path

    private data class ConnectionKey(
        val baseUrl: String,
        val bearerToken: String,
    )

    private companion object {
        val ShellStreamKinds = setOf(
            "snapshot",
            "project.updated",
            "project.removed",
            "thread.updated",
            "thread.removed",
            "synchronized",
        )
        val ThreadStreamKinds = setOf("snapshot", "event", "synchronized")
    }
}

/** Engines report a refused WebSocket upgrade only through their message, e.g. "expected 101 but was 426". */
internal fun Throwable.isUpgradeRequired(): Boolean =
    generateSequence(this) { cause -> cause.cause?.takeIf { it !== cause } }
        .any { Regex("\\b426\\b").containsMatchIn(it.message.orEmpty()) }

internal fun requireSupportedStreamKind(item: JsonElement, supportedKinds: Set<String>) {
    val kind = item.jsonObject["kind"]?.jsonPrimitive?.content
    if (kind != null && kind !in supportedKinds) {
        throw T3TransportException.UnsupportedStreamItem(kind)
    }
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
    private val receivedFrames = AtomicLong(0)
    private var heartbeat: Job? = null
    private val reader = scope.launch { readIncoming() }

    init {
        heartbeat = scope.launch {
            var lastSeen = receivedFrames.get()
            var silentBeats = 0
            while (!closed.get()) {
                delay(HeartbeatMillis)
                val seen = receivedFrames.get()
                silentBeats = if (seen == lastSeen) silentBeats + 1 else 0
                lastSeen = seen
                // A healthy server answers every ping, so silence means the host froze, slept or left the network.
                if (silentBeats >= SilentHeartbeatsBeforeTimeout) {
                    abandon(T3TransportException.Unresponsive((silentBeats * HeartbeatMillis / 1_000).toInt()))
                    return@launch
                }
                send(buildJsonObject { put("_tag", "Ping") })
            }
        }
    }

    private fun abandon(failure: T3TransportException) {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.close(failure) }
        pending.clear()
        session.cancel()
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
        // The socket opened, so a later loss is the host going away rather than the network path.
        var failure: Throwable = T3TransportException.Network(NetworkFailure.DISCONNECTED)
        try {
            for (frame in session.incoming) {
                receivedFrames.incrementAndGet()
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
            failure = when (cause) {
                is T3TransportException -> cause
                is SerializationException -> T3TransportException.InvalidResponse()
                else -> T3TransportException.Network(NetworkFailure.DISCONNECTED)
            }
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
            "Failure" -> throw decodeRpcFailure(exit["cause"] as? JsonArray)
            else -> throw T3TransportException.InvalidResponse()
        }
    }

    private fun JsonObject.tag(): String? = get("_tag")?.jsonPrimitive?.contentOrNull

    private data class PendingRequest(
        val id: String,
        val responses: Channel<JsonObject>,
    )

    private companion object {
        const val HeartbeatMillis = 10_000L
        const val SilentHeartbeatsBeforeTimeout = 3
    }
}

internal fun decodeRpcFailure(cause: JsonArray?): T3TransportException {
    val error = cause
        ?.firstOrNull { it.jsonObject["_tag"]?.jsonPrimitive?.contentOrNull == "Fail" }
        ?.jsonObject
        ?.get("error")
        ?.jsonObject
    if (error?.get("_tag")?.jsonPrimitive?.contentOrNull == "EnvironmentAuthorizationError") {
        val requiredScope = (error["requiredPermission"] ?: error["requiredScope"])?.jsonPrimitive?.contentOrNull
            ?: return T3TransportException.Rpc("T3 authorization failed.")
        return T3TransportException.Authorization(requiredScope)
    }
    val message = error?.get("message")?.jsonPrimitive?.contentOrNull
        ?.let(::redactTransportText)
        ?: "T3 RPC request failed."
    val traceId = (error?.get("traceId") ?: error?.get("trace_id"))
        ?.jsonPrimitive
        ?.contentOrNull
        ?.let(::redactTransportText)
    return T3TransportException.Rpc(message, traceId)
}
