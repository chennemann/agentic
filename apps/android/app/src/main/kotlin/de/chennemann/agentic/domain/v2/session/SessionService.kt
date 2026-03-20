package de.chennemann.agentic.domain.v2.session

import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import kotlinx.coroutines.flow.Flow

interface SessionService {
    fun sessionsOfProject(projectKey: String): Flow<List<LocalSessionInfo>>

    suspend fun syncSessionsOfProject(
        projectId: String,
        projectPath: String,
        baseUrl: String,
    )
}

class DefaultSessionService(
    private val sessionRepository: SessionRepository,
    private val adapter: OpenCodeServerAdapter,
) : SessionService {
    override fun sessionsOfProject(projectKey: String): Flow<List<LocalSessionInfo>> {
        return sessionRepository.sessionsOfProject(projectKey)
    }

    override suspend fun syncSessionsOfProject(
        projectId: String,
        projectPath: String,
        baseUrl: String,
    ) {
        val pid = projectId.trim()
        val path = projectPath.trim()
        val url = baseUrl.trim()
        if (pid.isBlank() || path.isBlank() || url.isBlank()) return

        val remoteSessions = adapter.allSessionsOfAGivenProject(url, path)
        remoteSessions.forEach { remote ->
            val sessionId = remote.id.trim()
            val sessionPath = remote.directory.trim()
            if (sessionId.isBlank() || sessionPath.isBlank()) return@forEach

            val existing = sessionRepository.selectStoredSession(sessionId)
            val local = LocalSessionRecord(
                id = sessionId,
                projectId = pid,
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
