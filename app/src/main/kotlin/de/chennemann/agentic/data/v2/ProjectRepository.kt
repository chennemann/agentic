package de.chennemann.agentic.data.v2

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.v2.projects.LocalProjectInfo
import de.chennemann.agentic.domain.v2.projects.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SqlDelightProjectRepository(
    private val db: AgenticDb,
    private val dispatchers: DispatcherProvider,
) : ProjectRepository {
    override fun observeProjects(serverId: String?): Flow<List<LocalProjectInfo>> {
        val id = serverId?.trim()?.ifBlank { null }
        val query = if (id == null) {
            db.projectsQueries.selectProjects(mapper = ::mapProject)
        } else {
            db.projectsQueries.selectProjectsByServer(server_id = id, mapper = ::mapProject)
        }
        return query
            .asFlow()
            .mapToList(dispatchers.io)
    }

    override suspend fun selectProject(id: String): LocalProjectInfo? {
        val projectId = id.trim()
        require(projectId.isNotBlank()) { "id must not be blank" }
        return withContext(dispatchers.io) {
            db.projectsQueries
                .selectProjectById(projectId, mapper = ::mapProject)
                .executeAsOneOrNull()
        }
    }

    override suspend fun insertProject(project: LocalProjectInfo) {
        withContext(dispatchers.io) {
            db.projectsQueries.insertProject(
                id = project.id,
                server_id = project.serverId,
                name = project.name,
                path = project.path,
                pinned = if (project.pinned) 1L else 0L,
            )
        }
    }

    override suspend fun updateProject(project: LocalProjectInfo) {
        withContext(dispatchers.io) {
            db.projectsQueries.updateProject(
                server_id = project.serverId,
                name = project.name,
                path = project.path,
                pinned = if (project.pinned) 1L else 0L,
                id = project.id,
            )
        }
    }

    override suspend fun deleteProject(id: String) {
        val projectId = id.trim()
        require(projectId.isNotBlank()) { "id must not be blank" }
        withContext(dispatchers.io) {
            db.projectsQueries.deleteProject(projectId)
        }
    }
}

private fun mapProject(
    id: String,
    server_id: String,
    name: String,
    path: String,
    pinned: Long,
): LocalProjectInfo {
    return LocalProjectInfo(
        id = id,
        serverId = server_id,
        name = name,
        path = path,
        pinned = pinned != 0L,
    )
}
