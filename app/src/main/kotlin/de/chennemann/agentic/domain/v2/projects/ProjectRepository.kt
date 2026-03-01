package de.chennemann.agentic.domain.v2.projects

import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeProjects(serverId: String? = null): Flow<List<LocalProjectInfo>>

    suspend fun selectProject(id: String): LocalProjectInfo?

    suspend fun insertProject(project: LocalProjectInfo)

    suspend fun updateProject(project: LocalProjectInfo)

    suspend fun deleteProject(id: String)
}
