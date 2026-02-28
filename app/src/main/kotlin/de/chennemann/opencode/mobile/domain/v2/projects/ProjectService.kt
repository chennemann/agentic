package de.chennemann.opencode.mobile.domain.v2.projects

import kotlinx.coroutines.flow.Flow

interface ProjectService {
    fun observeProjects(serverId: String? = null): Flow<List<LocalProjectInfo>>

    suspend fun togglePinnedById(projectId: String): Boolean
}

class DefaultProjectService(
    private val projectRepository: ProjectRepository,
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
}
