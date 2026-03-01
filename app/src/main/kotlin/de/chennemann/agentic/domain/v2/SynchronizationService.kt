package de.chennemann.agentic.domain.v2

import de.chennemann.agentic.domain.v2.projects.ProjectService
import de.chennemann.agentic.domain.v2.servers.ServerRepository
import de.chennemann.agentic.domain.v2.session.SessionService

interface SynchronizationService {
    suspend fun syncServer(serverId: String)
}

class DefaultSynchronizationService(
    private val serverRepository: ServerRepository,
    private val projectService: ProjectService,
    private val sessionService: SessionService,
) : SynchronizationService {
    override suspend fun syncServer(serverId: String) {
        val id = serverId.trim()
        if (id.isBlank()) return
        val server = serverRepository.selectServer(id) ?: return
        val url = server.url.trim()
        if (url.isBlank()) return

        val projects = projectService.syncServerProjects(id, url)
        projects.forEach { project ->
            sessionService.syncSessionsOfProject(
                projectId = project.id,
                projectPath = project.path,
                baseUrl = url,
            )
        }
    }
}
