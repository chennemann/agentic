package de.chennemann.agentic.domain.v2.projects

import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import de.chennemann.agentic.domain.v2.servers.ServerInfo
import de.chennemann.agentic.domain.v2.servers.ServerService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

interface ProjectService {

    val projects: Flow<List<LocalProjectInfo>>

    suspend fun togglePinnedById(projectId: String): Boolean

    suspend fun removeById(projectId: String): Boolean

    suspend fun syncServerProjects(serverId: String, baseUrl: String): List<LocalProjectInfo>
}

class DefaultProjectService(
    private val adapter: OpenCodeServerAdapter,
    private val serverService: ServerService,
    private val projectRepository: ProjectRepository,
) : ProjectService {

    override val projects: Flow<List<LocalProjectInfo>> = combine(serverService.connectedServer, projectRepository.observeProjects()) { connectedServer, projects ->
        when (connectedServer) {
            is ServerInfo.ConnectedServerInfo -> projects.filter { it.serverId == connectedServer.id }
            else -> emptyList()
        }
    }

    override suspend fun togglePinnedById(projectId: String): Boolean {
        val id = projectId.trim()
        if (id.isBlank()) return false
        val project = projectRepository.selectProject(id) ?: return false
        projectRepository.updateProject(
            project.copy(pinned = !project.pinned)
        )
        return true
    }

    override suspend fun removeById(projectId: String): Boolean {
        val id = projectId.trim()
        if (id.isBlank()) return false
        val project = projectRepository.selectProject(id) ?: return false
        projectRepository.deleteProject(project.id)
        return true
    }

    override suspend fun syncServerProjects(serverId: String, baseUrl: String): List<LocalProjectInfo> {
        val sid = serverId.trim()
        val url = baseUrl.trim()
        if (sid.isBlank() || url.isBlank()) return emptyList()

        val remoteProjects = adapter.allProjects(url)
        val synced = mutableListOf<LocalProjectInfo>()
        remoteProjects.forEach { remote ->
            val projectId = remote.id.trim()
            val projectPath = remote.worktree.trim()
            if (projectId.isBlank() || projectPath.isBlank()) return@forEach

            val existing = projectRepository.selectProject(projectId)
            val local = LocalProjectInfo(
                id = projectId,
                serverId = sid,
                name = remote.name.trim().ifBlank { projectPath },
                path = projectPath,
                pinned = existing?.pinned ?: false,
            )

            if (existing == null) {
                projectRepository.insertProject(local)
            } else {
                projectRepository.updateProject(local)
            }
            synced += local
        }
        return synced
    }
}
