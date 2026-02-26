package de.chennemann.opencode.mobile.data

import de.chennemann.opencode.mobile.api.apis.DefaultApi
import de.chennemann.opencode.mobile.api.models.SessionUpdateRequest
import de.chennemann.opencode.mobile.api.models.SessionUpdateRequestTime
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

data class Health(
    val healthy: Boolean,
    val version: String,
)

data class ProjectInfo(
    val id: String,
    val worktree: String,
    val name: String,
    val sandboxes: List<String> = emptyList(),
)

data class SessionInfo(
    val id: String,
    val title: String,
    val version: String,
    val directory: String,
    val parentId: String? = null,
    val updatedAt: Long? = null,
    val archivedAt: Long? = null,
)

data class SessionMessageInfo(
    val id: String,
    val role: String,
    val text: String,
    val parts: List<JsonObject>,
    val createdAt: Long? = null,
    val completedAt: Long? = null,
)

data class CommandInfo(
    val name: String,
    val description: String?,
    val source: String?,
)

data class GlobalStreamEvent(
    val directory: String,
    val type: String,
    val properties: JsonObject,
    val id: String?,
    val retry: Int?,
)

interface ServerGateway {
    suspend fun health(baseUrl: String): Health

    suspend fun projects(baseUrl: String): List<ProjectInfo>

    suspend fun sessions(baseUrl: String, worktree: String, limit: Int?): List<SessionInfo>

    suspend fun archiveSession(baseUrl: String, sessionId: String, directory: String)

    suspend fun renameSession(baseUrl: String, sessionId: String, directory: String, title: String)

    suspend fun createSession(baseUrl: String, worktree: String, title: String): SessionInfo

    suspend fun commands(baseUrl: String, directory: String): List<CommandInfo>

    suspend fun sessionMessages(baseUrl: String, sessionId: String, directory: String, limit: Int?): List<SessionMessageInfo>

    suspend fun sessionUpdatedAt(baseUrl: String, sessionId: String, directory: String): Long?

    suspend fun sessionStatus(baseUrl: String, directory: String): Map<String, String>

    suspend fun streamEvents(
        baseUrl: String,
        lastEventId: String?,
        onRawEvent: suspend (String) -> Unit,
        onEvent: suspend (GlobalStreamEvent) -> Unit,
    ): String?

    suspend fun sendMessage(baseUrl: String, sessionId: String, directory: String, text: String, agent: String)

    suspend fun sendCommand(baseUrl: String, sessionId: String, directory: String, name: String, arguments: String, agent: String)
}

class ServerService(
    private val json: Json,
    private val engine: HttpClientEngine,
) : ServerGateway {
    private val http = HttpClient(engine) {
        install(SSE) {
            maxReconnectionAttempts = Int.MAX_VALUE
            reconnectionTime = 3_000.milliseconds
            showCommentEvents()
            showRetryEvents()
        }
    }
    private var url: String? = null
    private var api: DefaultApi? = null

    private fun client(next: String): DefaultApi {
        if (url == next && api != null) return api!!
        val created = DefaultApi(
            baseUrl = next,
            httpClientEngine = engine,
        )
        api = created
        url = next
        return created
    }

    override suspend fun health(baseUrl: String): Health {
        val res = client(baseUrl).globalHealth()
        if (!res.success) {
            throw IllegalStateException("Server returned ${res.status}")
        }
        val body = res.response.bodyAsText()
        val obj = json.parseToJsonElement(body).jsonObject
        val healthy = obj["healthy"]?.jsonPrimitive?.booleanOrNull ?: false
        val version = obj["version"]?.jsonPrimitive?.content ?: "unknown"
        return Health(
            healthy = healthy,
            version = version,
        )
    }

    override suspend fun projects(baseUrl: String): List<ProjectInfo> {
        val res = client(baseUrl).projectList(null)
        if (!res.success) {
            throw IllegalStateException("Server returned ${res.status}")
        }
        return json
            .parseToJsonElement(res.response.bodyAsText())
            .jsonArray
            .mapNotNull {
                val obj = it.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val worktree = obj["worktree"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: worktree
                val sandboxes = projectDirectories(obj)
                ProjectInfo(
                    id = id,
                    worktree = worktree,
                    name = name,
                    sandboxes = sandboxes,
                )
            }
    }

    override suspend fun sessions(baseUrl: String, worktree: String, limit: Int?): List<SessionInfo> {
        val res = client(baseUrl).sessionList(worktree, true, null, null, limit?.let(::BigDecimal))
        if (!res.success) {
            throw IllegalStateException("Server returned ${res.status}")
        }
        return json
            .parseToJsonElement(res.response.bodyAsText())
            .jsonArray
            .mapNotNull {
                val obj = it.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Session"
                val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val directory = obj["directory"]?.jsonPrimitive?.contentOrNull ?: ""
                val parentId = obj["parentID"]?.jsonPrimitive?.contentOrNull
                    ?: obj["parentId"]?.jsonPrimitive?.contentOrNull
                    ?: obj["parent_id"]?.jsonPrimitive?.contentOrNull
                val updatedAt = (obj["time"] as? JsonObject)
                    ?.get("updated")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toLongOrNull()
                val archivedAt = (obj["time"] as? JsonObject)
                    ?.get("archived")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toLongOrNull()
                SessionInfo(
                    id = id,
                    title = title,
                    version = version,
                    directory = directory,
                    parentId = parentId,
                    updatedAt = updatedAt,
                    archivedAt = archivedAt,
                )
            }
    }

    override suspend fun archiveSession(baseUrl: String, sessionId: String, directory: String) {
        val res = client(baseUrl).sessionUpdate(
            sessionId,
            directory,
            SessionUpdateRequest(
                time = SessionUpdateRequestTime(
                    archived = BigDecimal(System.currentTimeMillis()),
                )
            ),
        )
        if (!res.success) {
            throw IllegalStateException("Server returned ${res.status}")
        }
    }

    override suspend fun renameSession(baseUrl: String, sessionId: String, directory: String, title: String) {
        val res = client(baseUrl).sessionUpdate(
            sessionId,
            directory,
            SessionUpdateRequest(
                title = title,
            ),
        )
        if (!res.success) {
            throw IllegalStateException("Server returned ${res.status}")
        }
    }

    override suspend fun createSession(baseUrl: String, worktree: String, title: String): SessionInfo {
        val res = http.post("$baseUrl/session") {
            parameter("directory", worktree)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("title", title)
                }.toString()
            )
        }
        if (res.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${res.status}")
        }
        val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("Invalid session payload")
        val value = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Session"
        val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val directory = obj["directory"]?.jsonPrimitive?.contentOrNull ?: ""
        return SessionInfo(
            id = id,
            title = value,
            version = version,
            directory = directory,
        )
    }

    override suspend fun commands(baseUrl: String, directory: String): List<CommandInfo> {
        val res = client(baseUrl).commandList(directory)
        if (!res.success) {
            throw IllegalStateException("Server returned ${res.status}")
        }
        return json
            .parseToJsonElement(res.response.bodyAsText())
            .jsonArray
            .mapNotNull {
                val obj = it.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val description = obj["description"]?.jsonPrimitive?.contentOrNull
                val source = obj["source"]?.jsonPrimitive?.contentOrNull
                CommandInfo(
                    name = name,
                    description = description,
                    source = source,
                )
            }
    }

    override suspend fun sessionMessages(baseUrl: String, sessionId: String, directory: String, limit: Int?): List<SessionMessageInfo> {
        val res = client(baseUrl).sessionMessages(sessionId, directory, limit?.let(::BigDecimal))
        if (!res.success) {
            throw IllegalStateException("Server returned ${res.status}")
        }
        return json
            .parseToJsonElement(res.response.bodyAsText())
            .jsonArray
            .mapNotNull {
                val row = it.jsonObject
                val info = row["info"]?.jsonObject ?: return@mapNotNull null
                val id = info["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val role = info["role"]?.jsonPrimitive?.contentOrNull ?: "assistant"
                val time = info["time"] as? JsonObject
                val createdAt = time?.get("created")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val completedAt = time?.get("completed")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val parts = row["parts"]?.jsonArray ?: return@mapNotNull null
                val raw = parts.map { it.jsonObject }
                val text = parts
                    .mapNotNull {
                        val obj = it.jsonObject
                        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        if (type != "text") return@mapNotNull null
                        obj["text"]?.jsonPrimitive?.contentOrNull
                    }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val value = if (text.isBlank()) {
                    val tags = parts
                        .mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }
                        .distinct()
                        .joinToString(", ")
                    if (tags.isBlank()) "(empty)" else "[$tags]"
                } else {
                    text
                }
                SessionMessageInfo(
                    id = id,
                    role = role,
                    text = value,
                    parts = raw,
                    createdAt = createdAt,
                    completedAt = completedAt,
                )
            }
    }

    override suspend fun sessionUpdatedAt(baseUrl: String, sessionId: String, directory: String): Long? {
        val res = http.get("$baseUrl/session/$sessionId") {
            parameter("directory", directory)
        }
        if (res.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${res.status}")
        }
        val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
        val time = obj["time"] as? JsonObject ?: return null
        val value = time["updated"]?.jsonPrimitive?.contentOrNull ?: return null
        return value.toLongOrNull()
    }

    override suspend fun sessionStatus(baseUrl: String, directory: String): Map<String, String> {
        val res = http.get("$baseUrl/session/status") {
            parameter("directory", directory)
        }
        if (res.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${res.status}")
        }
        val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
        return obj.mapNotNull { row ->
            val key = row.key.trim()
            if (key.isBlank()) return@mapNotNull null
            val value = (row.value as? JsonObject)
                ?.get("type")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: row.value.jsonPrimitive.contentOrNull
                ?: "unknown"
            key to value
        }.toMap()
    }

    override suspend fun streamEvents(
        baseUrl: String,
        lastEventId: String?,
        onRawEvent: suspend (String) -> Unit,
        onEvent: suspend (GlobalStreamEvent) -> Unit,
    ): String? {
        var cursor = lastEventId
        http.sse("$baseUrl/global/event", request = {
            header(HttpHeaders.CacheControl, "no-cache")
            if (!cursor.isNullOrBlank()) {
                header("Last-Event-ID", cursor)
            }
        }) {
            incoming.collect { event ->
                onRawEvent(
                    "id=${event.id} event=${event.event} retry=${event.retry} comments=${event.comments} data=${event.data}",
                )

                if (!event.id.isNullOrBlank()) {
                    cursor = event.id
                }

                val body = event.data ?: return@collect
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@collect
                val payload = root["payload"]?.jsonObject ?: return@collect
                val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: event.event ?: return@collect
                val properties = payload["properties"]?.jsonObject ?: JsonObject(emptyMap())
                onEvent(
                    GlobalStreamEvent(
                        directory = root["directory"]?.jsonPrimitive?.contentOrNull ?: "global",
                        type = type,
                        properties = properties,
                        id = event.id,
                        retry = event.retry?.toInt(),
                    )
                )
            }
        }

        return cursor
    }

    override suspend fun sendMessage(baseUrl: String, sessionId: String, directory: String, text: String, agent: String) {
        val res = http.post("$baseUrl/session/$sessionId/prompt_async") {
            parameter("directory", directory)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("agent", agent)
                    put(
                        "parts",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                }
                            )
                        }
                    )
                }.toString()
            )
        }
        if (res.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${res.status}")
        }
    }

    override suspend fun sendCommand(baseUrl: String, sessionId: String, directory: String, name: String, arguments: String, agent: String) {
        val res = http.post("$baseUrl/session/$sessionId/command") {
            parameter("directory", directory)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("command", name)
                    put("arguments", arguments)
                    put("agent", agent)
                }.toString()
            )
        }
        if (res.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${res.status}")
        }
    }
}

private fun projectDirectories(obj: JsonObject): List<String> {
    val sandboxes = parseDirectoryArray(obj["sandboxes"])
    if (sandboxes.isNotEmpty()) return sandboxes
    return parseDirectoryArray(obj["workspaces"])
}

private fun parseDirectoryArray(value: JsonElement?): List<String> {
    val array = value as? JsonArray ?: return emptyList()
    return array
        .mapNotNull {
            it.jsonPrimitive.contentOrNull
                ?: (it as? JsonObject)?.get("worktree")?.jsonPrimitive?.contentOrNull
                ?: (it as? JsonObject)?.get("directory")?.jsonPrimitive?.contentOrNull
        }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}
