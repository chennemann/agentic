package de.chennemann.agentic.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    engine: HttpClientEngine,
) : ServerGateway {
    private val http = HttpClient(engine) {
        install(SSE) {
            maxReconnectionAttempts = Int.MAX_VALUE
            reconnectionTime = 3_000.milliseconds
            showCommentEvents()
            showRetryEvents()
        }
    }
    private val sessionDirectories = mutableMapOf<String, String>()

    override suspend fun health(baseUrl: String): Health {
        val response = http.get("$baseUrl/up")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val healthy = obj["status"]?.jsonPrimitive?.contentOrNull == "ok"
        val version = obj["server"]
            ?.jsonObject
            ?.get("version")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: "unknown"
        return Health(healthy = healthy, version = version)
    }

    override suspend fun projects(baseUrl: String): List<ProjectInfo> {
        val response = http.get("$baseUrl/projects")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val rows = root["projects"]?.jsonArray ?: JsonArray(emptyList())
        return rows.mapNotNull { row ->
            val obj = row.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val worktree = obj["cwd"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ProjectInfo(
                id = id,
                worktree = worktree,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: worktree,
                sandboxes = projectDirectories(obj),
            )
        }
    }

    override suspend fun sessions(baseUrl: String, worktree: String, limit: Int?): List<SessionInfo> {
        val project = projects(baseUrl)
            .firstOrNull { candidate ->
                candidate.worktree == worktree || candidate.sandboxes.any { it == worktree }
            }
            ?: return emptyList()
        val response = http.get("$baseUrl/projects/${project.id}/sessions")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val rows = root["sessions"]?.jsonArray ?: JsonArray(emptyList())
        return rows.mapNotNull(::mapSession)
            .let { sessions -> if (limit == null) sessions else sessions.take(limit) }
            .onEach { sessionDirectories[it.id] = it.directory }
    }

    override suspend fun archiveSession(baseUrl: String, sessionId: String, directory: String) {
        updateSession(baseUrl, sessionId) {
            put("archived", true)
        }
    }

    override suspend fun renameSession(baseUrl: String, sessionId: String, directory: String, title: String) {
        updateSession(baseUrl, sessionId) {
            put("title", title)
        }
    }

    override suspend fun createSession(baseUrl: String, worktree: String, title: String): SessionInfo {
        val response = http.post("$baseUrl/sessions") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("cwd", worktree)
                    put("title", title)
                }.toString(),
            )
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val session = mapSession(root["session"] ?: error("Invalid session payload")) ?: error("Invalid session payload")
        sessionDirectories[session.id] = session.directory
        return session
    }

    override suspend fun commands(baseUrl: String, directory: String): List<CommandInfo> {
        return emptyList()
    }

    override suspend fun sessionMessages(baseUrl: String, sessionId: String, directory: String, limit: Int?): List<SessionMessageInfo> {
        val response = http.get("$baseUrl/sessions/$sessionId/messages")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        root["session"]?.let { mapSession(it) }?.let { sessionDirectories[it.id] = it.directory }
        val rows = root["messages"]?.jsonArray ?: JsonArray(emptyList())
        val messages = rows.mapNotNull { row ->
            val obj = row.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "assistant"
            val raw = obj["raw"]?.jsonObject
            val parts = extractParts(id, role, obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(), raw)
            val text = extractText(parts)
            SessionMessageInfo(
                id = id,
                role = role,
                text = text,
                parts = parts,
                createdAt = isoToMillis(obj["createdAt"]?.jsonPrimitive?.contentOrNull),
                completedAt = isoToMillis(obj["completedAt"]?.jsonPrimitive?.contentOrNull),
            )
        }
        return if (limit == null) messages else messages.takeLast(limit)
    }

    override suspend fun sessionUpdatedAt(baseUrl: String, sessionId: String, directory: String): Long? {
        val response = http.get("$baseUrl/sessions/$sessionId")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val session = root["session"]?.jsonObject ?: return null
        sessionDirectories[sessionId] = session["cwd"]?.jsonPrimitive?.contentOrNull ?: directory
        return isoToMillis(session["updatedAt"]?.jsonPrimitive?.contentOrNull)
    }

    override suspend fun sessionStatus(baseUrl: String, directory: String): Map<String, String> {
        return sessions(baseUrl, directory, null)
            .filter { it.archivedAt == null }
            .associate { it.id to "idle" }
    }

    override suspend fun streamEvents(
        baseUrl: String,
        lastEventId: String?,
        onRawEvent: suspend (String) -> Unit,
        onEvent: suspend (GlobalStreamEvent) -> Unit,
    ): String? {
        var cursor = lastEventId
        http.sse("$baseUrl/events", request = {
            header(HttpHeaders.CacheControl, "no-cache")
            if (!cursor.isNullOrBlank()) {
                header("Last-Event-ID", cursor)
            }
        }) {
            incoming.collect { event ->
                onRawEvent("id=${event.id} event=${event.event} retry=${event.retry} comments=${event.comments} data=${event.data}")
                if (!event.id.isNullOrBlank()) {
                    cursor = event.id
                }
                val body = event.data ?: return@collect
                val envelope = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@collect
                val sessionId = envelope["sessionId"]?.jsonPrimitive?.contentOrNull ?: return@collect
                val emittedAt = envelope["emittedAt"]?.jsonPrimitive?.contentOrNull
                val rawEvent = envelope["event"]?.jsonObject ?: return@collect
                val mapped = mapStreamEvent(baseUrl, sessionId, emittedAt, rawEvent, event.id, event.retry?.toInt()) ?: return@collect
                onEvent(mapped)
            }
        }
        return cursor
    }

    override suspend fun sendMessage(baseUrl: String, sessionId: String, directory: String, text: String, agent: String) {
        val response = http.post("$baseUrl/sessions/$sessionId/messages") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("text", text)
                    put("mode", agent)
                }.toString(),
            )
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
    }

    override suspend fun sendCommand(baseUrl: String, sessionId: String, directory: String, name: String, arguments: String, agent: String) {
        val command = buildString {
            append('/')
            append(name)
            if (arguments.isNotBlank()) {
                append(' ')
                append(arguments)
            }
        }
        sendMessage(baseUrl, sessionId, directory, command, agent)
    }

    private suspend fun updateSession(baseUrl: String, sessionId: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val response = http.patch("$baseUrl/sessions/$sessionId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject(block).toString())
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        root["session"]?.let { mapSession(it) }?.let { sessionDirectories[it.id] = it.directory }
    }

    private suspend fun mapStreamEvent(
        baseUrl: String,
        sessionId: String,
        emittedAt: String?,
        rawEvent: JsonObject,
        eventId: String?,
        retry: Int?,
    ): GlobalStreamEvent? {
        val type = rawEvent["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val directory = resolveDirectory(baseUrl, sessionId)
        val message = rawEvent["message"]?.jsonObject
        return when (type) {
            "agent_start" -> GlobalStreamEvent(
                directory = directory,
                type = "session.status",
                properties = buildJsonObject {
                    put("sessionID", sessionId)
                    put("status", buildJsonObject { put("type", "busy") })
                },
                id = eventId,
                retry = retry,
            )

            "agent_end" -> GlobalStreamEvent(
                directory = directory,
                type = "session.idle",
                properties = buildJsonObject {
                    put("sessionID", sessionId)
                },
                id = eventId,
                retry = retry,
            )

            "message_start", "message_update", "message_end" -> {
                val messageId = message?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
                val role = message["role"]?.jsonPrimitive?.contentOrNull ?: "assistant"
                val text = extractMessageText(message)
                GlobalStreamEvent(
                    directory = directory,
                    type = "message.updated",
                    properties = buildJsonObject {
                        put(
                            "info",
                            buildJsonObject {
                                put("sessionID", sessionId)
                                put("id", messageId)
                                put("role", role)
                                put("text", text)
                                put(
                                    "time",
                                    buildJsonObject {
                                        if (type == "message_start") {
                                            isoToMillis(emittedAt)?.let { put("created", it) }
                                        }
                                        if (type == "message_end") {
                                            isoToMillis(emittedAt)?.let { put("completed", it) }
                                        }
                                    },
                                )
                            },
                        )
                    },
                    id = eventId,
                    retry = retry,
                )
            }

            else -> null
        }
    }

    private suspend fun resolveDirectory(baseUrl: String, sessionId: String): String {
        sessionDirectories[sessionId]?.let { return it }
        val response = http.get("$baseUrl/sessions/$sessionId")
        if (response.status.value !in 200..299) {
            return "global"
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val directory = root["session"]
            ?.jsonObject
            ?.get("cwd")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: "global"
        sessionDirectories[sessionId] = directory
        return directory
    }
}


private fun mapSession(value: JsonElement): SessionInfo? {
    val obj = value.jsonObject
    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
    return SessionInfo(
        id = id,
        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Session",
        version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "mock",
        directory = obj["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        parentId = obj["parentSessionId"]?.jsonPrimitive?.contentOrNull,
        updatedAt = isoToMillis(obj["updatedAt"]?.jsonPrimitive?.contentOrNull),
        archivedAt = isoToMillis(obj["archivedAt"]?.jsonPrimitive?.contentOrNull),
    )
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

private fun isoToMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: value.toLongOrNull()
}

private fun extractParts(messageId: String, role: String, fallbackText: String, raw: JsonObject?): List<JsonObject> {
    val content = raw?.get("content") as? JsonArray
    val rawParts = content
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    if (rawParts.isNotEmpty()) return rawParts
    if (fallbackText.isBlank()) return emptyList()
    return listOf(
        buildJsonObject {
            put("id", "$messageId:text")
            put("type", "text")
            put("role", role)
            put("text", fallbackText)
        },
    )
}

private fun extractText(parts: List<JsonObject>): String {
    val text = parts
        .mapNotNull { part ->
            if (part["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
            part["text"]?.jsonPrimitive?.contentOrNull
        }
        .filter { it.isNotBlank() }
        .joinToString("\n")
    if (text.isNotBlank()) return text
    val tags = parts
        .mapNotNull { it["type"]?.jsonPrimitive?.contentOrNull }
        .distinct()
        .joinToString(", ")
    return if (tags.isBlank()) "(empty)" else "[$tags]"
}

private fun extractMessageText(message: JsonObject): String {
    val content = message["content"] as? JsonArray ?: return ""
    return content
        .mapNotNull { item ->
            val part = item as? JsonObject ?: return@mapNotNull null
            if (part["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
            part["text"]?.jsonPrimitive?.contentOrNull
        }
        .joinToString(separator = "")
}
