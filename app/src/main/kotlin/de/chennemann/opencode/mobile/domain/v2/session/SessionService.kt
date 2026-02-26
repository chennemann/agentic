package de.chennemann.opencode.mobile.domain.v2.session

import kotlinx.coroutines.flow.Flow

interface SessionService {
    fun sessionsOfProject(projectKey: String): Flow<List<LocalSessionInfo>>
}

class DefaultSessionService(
    private val sessionRepository: SessionRepository,
) : SessionService {
    override fun sessionsOfProject(projectKey: String): Flow<List<LocalSessionInfo>> {
        return sessionRepository.sessionsOfProject(projectKey)
    }
}
