package de.chennemann.opencode.mobile.data.v2

import de.chennemann.opencode.mobile.api.apis.DefaultApi
import de.chennemann.opencode.mobile.api.apis.SessionApi
import de.chennemann.opencode.mobile.api.models.GlobalHealth200Response
import de.chennemann.opencode.mobile.api.models.Project
import de.chennemann.opencode.mobile.api.models.Session
import de.chennemann.opencode.mobile.domain.v2.OpenCodeHealthCheck
import de.chennemann.opencode.mobile.domain.v2.OpenCodeProject
import de.chennemann.opencode.mobile.domain.v2.OpenCodeSession
import de.chennemann.opencode.mobile.domain.v2.OpenCodeServerAdapter
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header

class OpenApiServerAdapter(
    private val engine: HttpClientEngine,
) : OpenCodeServerAdapter {
    private val lock = Any()
    private val defaultClients = mutableMapOf<String, DefaultApi>()
    private val defaultClientsWithDirectory = mutableMapOf<String, DefaultApi>()
    private val sessionClients = mutableMapOf<String, SessionApi>()

    override suspend fun healthCheckWithUrl(baseUrl: String): OpenCodeHealthCheck {
        val response = defaultApi(baseUrl).globalHealth()
        if (!response.success) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        val body = response.body()
        return OpenCodeHealthCheck(
            healthy = body.healthy == GlobalHealth200Response.Healthy.`true`,
            version = body.version,
        )
    }

    override suspend fun allProjects(baseUrl: String): List<OpenCodeProject> {
        val response = defaultApi(baseUrl).projectList(directory = null)
        if (!response.success) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        return response.body().map(::mapProject)
    }

    override suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<OpenCodeSession> {
        val directory = normalizeDirectory(path)
        val response = defaultApiWithDirectory(baseUrl, directory).sessionList(
            directory = null,
            roots = null,
            start = null,
            search = null,
            limit = null,
        )
        if (!response.success) {
            throw IllegalStateException("Server returned ${response.status}")
        }
        return response.body().map(::mapSession)
    }

    private fun defaultApi(baseUrl: String): DefaultApi {
        val key = normalizeBaseUrl(baseUrl)
        return synchronized(lock) {
            defaultClients.getOrPut(key) {
                DefaultApi(
                    baseUrl = key,
                    httpClientEngine = engine,
                )
            }
        }
    }

    private fun defaultApiWithDirectory(baseUrl: String, directory: String): DefaultApi {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val key = "$normalizedBaseUrl|$directory"
        return synchronized(lock) {
            defaultClientsWithDirectory.getOrPut(key) {
                DefaultApi(
                    baseUrl = normalizedBaseUrl,
                    httpClientEngine = engine,
                    httpClientConfig = {
                        it.defaultRequest {
                            header(OPENCODE_DIRECTORY_HEADER, directory)
                        }
                    },
                )
            }
        }
    }

    @Suppress("unused")
    private fun sessionApi(baseUrl: String): SessionApi {
        val key = normalizeBaseUrl(baseUrl)
        return synchronized(lock) {
            sessionClients.getOrPut(key) {
                SessionApi(
                    baseUrl = key,
                    httpClientEngine = engine,
                )
            }
        }
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

private fun mapProject(value: Project): OpenCodeProject {
    return OpenCodeProject(
        id = value.id,
        worktree = value.worktree,
        name = value.name ?: value.worktree,
        sandboxes = value.sandboxes,
    )
}

private fun mapSession(value: Session): OpenCodeSession {
    return OpenCodeSession(
        id = value.id,
        projectId = value.projectID,
        directory = value.directory,
        title = value.title,
        version = value.version,
        parentId = value.parentID,
        updatedAt = value.time.updated.toLong(),
        archivedAt = value.time.archived?.toLong(),
    )
}

private const val OPENCODE_DIRECTORY_HEADER = "x-opencode-directory"
