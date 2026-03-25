package de.chennemann.agentic.data.v2

import de.chennemann.agentic.domain.v2.OpenCodeHealthCheck
import de.chennemann.agentic.domain.v2.OpenCodeProject
import de.chennemann.agentic.domain.v2.OpenCodeSession
import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenApiServerAdapter(
    engine: HttpClientEngine,
) : OpenCodeServerAdapter {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(engine)

    override suspend fun healthCheckWithUrl(baseUrl: String): OpenCodeHealthCheck {
        val response = http.get("${normalizeBaseUrl(baseUrl)}/up")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        val body = parseObject(response.bodyAsText())
        return OpenCodeHealthCheck(
            healthy = body["status"]?.jsonPrimitive?.contentOrNull == "ok",
            version = body["server"]
                ?.jsonObject
                ?.get("version")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: "unknown",
        )
    }

    override suspend fun allProjects(baseUrl: String): List<OpenCodeProject> {
        val response = http.get("${normalizeBaseUrl(baseUrl)}/projects")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        return parseObject(response.bodyAsText())["projects"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { row ->
                val obj = row.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val worktree = obj["cwd"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                OpenCodeProject(
                    id = id,
                    worktree = worktree,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: worktree,
                    sandboxes = emptyList(),
                )
            }
    }

    override suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<OpenCodeSession> {
        val project = allProjects(baseUrl).firstOrNull { it.worktree == normalizeDirectory(path) } ?: return emptyList()
        val response = http.get("${normalizeBaseUrl(baseUrl)}/projects/${project.id}/sessions")
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        return parseObject(response.bodyAsText())["sessions"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull(::mapSession)
    }

    private fun parseObject(value: String): JsonObject {
        return json.parseToJsonElement(value).jsonObject
    }
}

private fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotBlank()) { "baseUrl must not be blank" }
    return trimmed.replace(Regex("/+$"), "")
}

private fun normalizeDirectory(value: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotBlank()) { "path must not be blank" }
    return trimmed
}

private fun mapSession(value: JsonElement): OpenCodeSession? {
    val obj = value.jsonObject
    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val directory = obj["cwd"]?.jsonPrimitive?.contentOrNull ?: return null
    return OpenCodeSession(
        id = id,
        projectId = obj["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        directory = directory,
        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: directory,
        version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "mock",
        parentId = obj["parentSessionId"]?.jsonPrimitive?.contentOrNull,
        updatedAt = isoToMillis(obj["updatedAt"]?.jsonPrimitive?.contentOrNull),
        archivedAt = isoToMillis(obj["archivedAt"]?.jsonPrimitive?.contentOrNull),
    )
}

private fun isoToMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: value.toLongOrNull()
}

private fun JsonElement?.orEmpty(): JsonArray {
    return this as? JsonArray ?: JsonArray(emptyList())
}
