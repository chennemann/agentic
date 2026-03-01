package de.chennemann.agentic.data.v2

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.v2.session.LocalSessionInfo
import de.chennemann.agentic.domain.v2.session.LocalSessionRecord
import de.chennemann.agentic.domain.v2.session.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SqlDelightSessionRepository(
    private val db: AgenticDb,
    private val dispatchers: DispatcherProvider,
) : SessionRepository {
    override fun sessionsOfProject(projectKey: String): Flow<List<LocalSessionInfo>> {
        val key = projectKey.trim()
        require(key.isNotBlank()) { "projectKey must not be blank" }
        return db.sessionCacheQueries
            .listLocalSessionByProject(
                project_key = key,
                mapper = ::mapLocalSession,
            )
            .asFlow()
            .mapToList(dispatchers.io)
    }

    override fun observeStoredSessions(projectId: String?): Flow<List<LocalSessionRecord>> {
        val id = projectId?.trim()?.ifBlank { null }
        val query = if (id == null) {
            db.sessionsQueries.selectSessions(mapper = ::mapStoredSession)
        } else {
            db.sessionsQueries.selectSessionsByProject(project_id = id, mapper = ::mapStoredSession)
        }
        return query
            .asFlow()
            .mapToList(dispatchers.io)
    }

    override suspend fun selectStoredSession(id: String): LocalSessionRecord? {
        val sessionId = id.trim()
        require(sessionId.isNotBlank()) { "id must not be blank" }
        return withContext(dispatchers.io) {
            db.sessionsQueries
                .selectSessionById(sessionId, mapper = ::mapStoredSession)
                .executeAsOneOrNull()
        }
    }

    override suspend fun insertStoredSession(session: LocalSessionRecord) {
        withContext(dispatchers.io) {
            db.sessionsQueries.insertSession(
                id = session.id,
                project_id = session.projectId,
                title = session.title,
                path = session.path,
                pinned = if (session.pinned) 1L else 0L,
            )
        }
    }

    override suspend fun updateStoredSession(session: LocalSessionRecord) {
        withContext(dispatchers.io) {
            db.sessionsQueries.updateSession(
                project_id = session.projectId,
                title = session.title,
                path = session.path,
                pinned = if (session.pinned) 1L else 0L,
                id = session.id,
            )
        }
    }

    override suspend fun deleteStoredSession(id: String) {
        val sessionId = id.trim()
        require(sessionId.isNotBlank()) { "id must not be blank" }
        withContext(dispatchers.io) {
            db.sessionsQueries.deleteSession(sessionId)
        }
    }
}

private fun mapLocalSession(
    id: String,
    project_id: String,
    workspace: String,
    title: String,
    pinned: Long,
    parent_id: String?,
    updated_at: Long,
    last_read_at: Long?,
    archived_at: Long?,
): LocalSessionInfo {
    return LocalSessionInfo(
        id = id,
        projectId = project_id,
        workspace = workspace,
        title = title,
        pinned = pinned != 0L,
        parentId = parent_id,
        updatedAt = updated_at,
        lastReadAt = last_read_at,
        archivedAt = archived_at,
    )
}

private fun mapStoredSession(
    id: String,
    project_id: String,
    title: String,
    path: String,
    pinned: Long,
): LocalSessionRecord {
    return LocalSessionRecord(
        id = id,
        projectId = project_id,
        title = title,
        path = path,
        pinned = pinned != 0L,
    )
}
