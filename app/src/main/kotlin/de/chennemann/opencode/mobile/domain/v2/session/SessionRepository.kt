package de.chennemann.opencode.mobile.domain.v2.session

import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun sessionsOfProject(projectKey: String): Flow<List<LocalSessionInfo>>

    fun observeStoredSessions(projectId: String? = null): Flow<List<LocalSessionRecord>>

    suspend fun selectStoredSession(id: String): LocalSessionRecord?

    suspend fun insertStoredSession(session: LocalSessionRecord)

    suspend fun updateStoredSession(session: LocalSessionRecord)

    suspend fun deleteStoredSession(id: String)
}
