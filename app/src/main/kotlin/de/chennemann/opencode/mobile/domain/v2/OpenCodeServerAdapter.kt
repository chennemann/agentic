package de.chennemann.opencode.mobile.domain.v2

interface OpenCodeServerAdapter {
    suspend fun healthCheckWithUrl(baseUrl: String): OpenCodeHealthCheck

    suspend fun allProjects(baseUrl: String): List<OpenCodeProject>

    suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<OpenCodeSession>
}

data class OpenCodeHealthCheck(
    val healthy: Boolean,
    val version: String,
)

data class OpenCodeProject(
    val id: String,
    val worktree: String,
    val name: String,
    val sandboxes: List<String>,
)

data class OpenCodeSession(
    val id: String,
    val projectId: String,
    val directory: String,
    val title: String,
    val version: String,
    val parentId: String? = null,
    val updatedAt: Long? = null,
    val archivedAt: Long? = null,
)
