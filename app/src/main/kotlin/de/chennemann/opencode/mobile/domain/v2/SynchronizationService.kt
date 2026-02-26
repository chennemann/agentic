package de.chennemann.opencode.mobile.domain.v2

import de.chennemann.opencode.mobile.domain.v2.projects.ProjectRepository
import de.chennemann.opencode.mobile.domain.v2.projects.LocalProjectInfo
import de.chennemann.opencode.mobile.domain.v2.servers.ServerRepository
import de.chennemann.opencode.mobile.domain.v2.session.LocalSessionRecord
import de.chennemann.opencode.mobile.domain.v2.session.SessionRepository

interface SynchronizationService {
    suspend fun syncServer(serverId: String)
}

class DefaultSynchronizationService(
    private val serverRepository: ServerRepository,
    private val projectRepository: ProjectRepository,
    private val sessionRepository: SessionRepository,
    private val adapter: OpenCodeServerAdapter,
) : SynchronizationService {
    override suspend fun syncServer(serverId: String) {
        val id = serverId.trim()
        if (id.isBlank()) return
        val server = serverRepository.selectServer(id) ?: return
        val url = server.url.trim()
        if (url.isBlank()) return

        syncProjectsForServer(id, url)
    }

    private suspend fun syncProjectsForServer(serverId: String, url: String) {
        val remoteProjects = adapter.allProjects(url)
        remoteProjects.forEach { remote ->
            val projectId = remote.id.trim()
            val projectPath = remote.worktree.trim()
            if (projectId.isBlank() || projectPath.isBlank()) return@forEach

            val existing = projectRepository.selectProject(projectId)
            val local = LocalProjectInfo(
                id = projectId,
                serverId = serverId,
                name = remote.name.trim().ifBlank { projectPath },
                path = projectPath,
                pinned = existing?.pinned ?: false,
            )

            if (existing == null) {
                projectRepository.insertProject(local)
            } else {
                projectRepository.updateProject(local)
            }

            syncSessionsForProject(projectId, url)
        }
    }

    private suspend fun syncSessionsForProject(projectId: String, url: String) {
        val id = projectId.trim()
        if (id.isBlank()) return
        val baseUrl = url.trim()
        if (baseUrl.isBlank()) return
        val project = projectRepository.selectProject(id) ?: return
        val projectPath = project.path.trim()
        if (projectPath.isBlank()) return

        val remoteSessions = adapter.allSessionsOfAGivenProject(baseUrl, projectPath)
        remoteSessions.forEach { remote ->
            val sessionId = remote.id.trim()
            val sessionPath = remote.directory.trim()
            if (sessionId.isBlank() || sessionPath.isBlank()) return@forEach

            val existing = sessionRepository.selectStoredSession(sessionId)
            val local = LocalSessionRecord(
                id = sessionId,
                projectId = id,
                title = remote.title.trim().ifBlank { sessionPath },
                path = sessionPath,
                pinned = existing?.pinned ?: false,
            )

            if (existing == null) {
                sessionRepository.insertStoredSession(local)
            } else {
                sessionRepository.updateStoredSession(local)
            }
        }
    }
}
