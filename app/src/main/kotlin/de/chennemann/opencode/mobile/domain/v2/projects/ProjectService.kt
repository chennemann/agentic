package de.chennemann.opencode.mobile.domain.v2.projects

import de.chennemann.opencode.mobile.domain.v2.OpenCodeServerAdapter
import kotlinx.coroutines.flow.Flow

interface ProjectService {
    fun observeProjects(serverId: String? = null): Flow<List<LocalProjectInfo>>

    suspend fun togglePinnedById(projectId: String): Boolean

    suspend fun syncServerProjects(serverId: String, baseUrl: String): List<LocalProjectInfo>
}

class DefaultProjectService(
    private val projectRepository: ProjectRepository,
    private val adapter: OpenCodeServerAdapter,
) : ProjectService {
    override fun observeProjects(serverId: String?): Flow<List<LocalProjectInfo>> {
        return projectRepository.observeProjects(serverId)
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
