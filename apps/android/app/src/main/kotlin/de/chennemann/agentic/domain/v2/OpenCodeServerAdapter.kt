package de.chennemann.agentic.domain.remote

interface ServerAdapter {
    suspend fun healthCheckWithUrl(baseUrl: String): ServerHealthCheck

    suspend fun allProjects(baseUrl: String): List<ServerProject>

    suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<ServerSession>
}

data class ServerHealthCheck(
    val healthy: Boolean,
    val version: String,
)

data class ServerProject(
    val id: String,
    val worktree: String,
    val name: String,
    val sandboxes: List<String>,
)

data class ServerSession(
    val id: String,
    val projectId: String,
    val directory: String,
    val title: String,
    val version: String,
    val parentId: String? = null,
    val updatedAt: Long? = null,
    val archivedAt: Long? = null,
)
