package de.chennemann.agentic.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.session.MessageState
import de.chennemann.agentic.domain.session.RecentSessionCache
import de.chennemann.agentic.domain.session.SessionCacheGateway
import de.chennemann.agentic.domain.session.SessionQuickPinCache
import de.chennemann.agentic.domain.session.SessionState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

class SessionCacheRepository(
    private val db: AgenticDb,
    private val dispatchers: DispatcherProvider,
) : SessionCacheGateway {
    override suspend fun upsertSession(server: String, project: String?, session: SessionState) {
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            db.sessionCacheQueries.upsertSessionCache(
                server,
                session.id,
                project,
                session.directory,
                session.title,
                session.version,
                now,
                now,
            )
        }
    }

    override suspend fun upsertSessionSnapshot(server: String, project: String?, session: SessionState) {
        withContext(dispatchers.io) {
            db.sessionCacheQueries.upsertSessionCacheSnapshot(
                server,
                session.id,
                project,
                session.directory,
                session.title,
                session.version,
                session.updatedAt ?: System.currentTimeMillis(),
            )
        }
    }

    override suspend fun syncProjectSessions(server: String, project: String, sessions: List<SessionState>) {
        withContext(dispatchers.io) {
            val next = sessions
                .groupBy { it.id }
                .mapNotNull {
                    it.value.maxWithOrNull(
                        compareBy<SessionState>({ value -> value.updatedAt ?: 0L }, { value -> value.id })
                    )
                }
            val nextIds = next.map { it.id }.toSet()
            val currentIds = db.sessionCacheQueries
                .listProjectSessionCache(server, project, mapper = ::mapSessionCache)
                .executeAsList()
                .map { it.id }

            currentIds
                .filterNot(nextIds::contains)
                .forEach {
                    db.sessionCacheQueries.deleteSessionCache(server, it)
                    db.messageCacheQueries.deleteMessageCacheSession(server, it)
                }

            next.forEach {
                db.sessionCacheQueries.upsertSessionCacheSnapshot(
                    server,
                    it.id,
                    project,
                    it.directory,
                    it.title,
                    it.version,
                    it.updatedAt ?: System.currentTimeMillis(),
                )
            }
        }
    }

    override suspend fun listProjectSessions(server: String, project: String, limit: Int?): List<SessionState> {
        return withContext(dispatchers.io) {
            if (limit == null) {
                db.sessionCacheQueries.listProjectSessionCache(server, project, mapper = ::mapSessionCache)
                    .executeAsList()
            } else {
                db.sessionCacheQueries.listProjectSessionCacheLimited(server, project, limit.toLong(), mapper = ::mapSessionCache)
                    .executeAsList()
            }
        }
    }

    override suspend fun deleteSession(server: String, sessionId: String) {
        withContext(dispatchers.io) {
            db.sessionCacheQueries.deleteSessionCache(server, sessionId)
        }
    }

    override fun recentSession(): RecentSessionCache? {
        return db.sessionCacheQueries.selectRecentSessionCache(
            mapper = { serverUrl, sessionId, projectId, directory, title, version, _, _ ->
                RecentSessionCache(
                    server = serverUrl,
                    project = projectId,
                    session = SessionState(
                        id = sessionId,
                        title = title,
                        version = version,
                        directory = directory,
                    ),
                )
            }
        ).executeAsOneOrNull()
    }

    override fun projectFavorites(server: String): Set<String> {
        return settingSet(db, projectFavoriteKey(server))
    }

    override suspend fun setProjectFavorite(server: String, worktree: String, favorite: Boolean) {
        withContext(dispatchers.io) {
            val key = projectFavoriteKey(server)
            val next = projectFavorites(server)
                .toMutableSet()
                .also {
                    if (favorite) it.add(worktree) else it.remove(worktree)
                }
                .toList()
                .sorted()

            if (next.isEmpty()) {
                db.settingsQueries.deleteSetting(key)
                return@withContext
            }

            db.settingsQueries.upsertSetting(key, next.joinToString(ProjectFavoriteSeparator))
        }
    }

    override fun hiddenProjects(server: String): Set<String> {
        return settingSet(db, projectHiddenKey(server))
    }

    override suspend fun setProjectHidden(server: String, worktree: String, hidden: Boolean) {
        withContext(dispatchers.io) {
            val key = projectHiddenKey(server)
            val next = hiddenProjects(server)
                .toMutableSet()
                .also {
                    if (hidden) it.add(worktree) else it.remove(worktree)
                }
                .toList()
                .sorted()

            if (next.isEmpty()) {
                db.settingsQueries.deleteSetting(key)
                return@withContext
            }

            db.settingsQueries.upsertSetting(key, next.joinToString(ProjectFavoriteSeparator))
        }
    }

    override fun sessionQuickPins(server: String): SessionQuickPinCache {
        return SessionQuickPinCache(
            include = settingSet(db, sessionQuickIncludeKey(server)),
            exclude = settingSet(db, sessionQuickExcludeKey(server)),
        )
    }

    override suspend fun setSessionQuickPins(server: String, include: Set<String>, exclude: Set<String>) {
        withContext(dispatchers.io) {
            setSettingSet(db, sessionQuickIncludeKey(server), include)
            setSettingSet(db, sessionQuickExcludeKey(server), exclude)
        }
    }

    override suspend fun listMessages(server: String, sessionId: String): List<MessageState> {
        return withContext(dispatchers.io) {
            db.messageCacheQueries
                .listMessageCache(server, sessionId) { _, _, messageId, role, text, sortKey, createdAt, completedAt, _ ->
                    MessageState(
                        id = messageId,
                        role = role,
                        text = text,
                        sort = sortKey,
                        createdAt = createdAt,
                        completedAt = completedAt,
                    )
                }
                .executeAsList()
        }
    }

    override fun observeMessages(server: String, sessionId: String): Flow<List<MessageState>> {
        return db.messageCacheQueries
            .listMessageCache(server, sessionId) { _, _, messageId, role, text, sortKey, createdAt, completedAt, _ ->
                MessageState(
                    id = messageId,
                    role = role,
                    text = text,
                    sort = sortKey,
                    createdAt = createdAt,
                    completedAt = completedAt,
                )
            }
            .asFlow()
            .mapToList(dispatchers.io)
    }

    override suspend fun upsertMessage(server: String, sessionId: String, message: MessageState, updatedAt: Long) {
        withContext(dispatchers.io) {
            db.messageCacheQueries.upsertMessageCache(
                server,
                sessionId,
                message.id,
                message.role,
                message.text,
                message.sort,
                message.createdAt,
                message.completedAt,
                updatedAt,
            )
        }
    }

    override suspend fun deleteMessage(server: String, sessionId: String, messageId: String) {
        withContext(dispatchers.io) {
            db.messageCacheQueries.deleteMessageCache(server, sessionId, messageId)
        }
    }

    override suspend fun deleteSessionMessages(server: String, sessionId: String) {
        withContext(dispatchers.io) {
            db.messageCacheQueries.deleteMessageCacheSession(server, sessionId)
        }
    }
}

private fun mapSessionCache(
    server: String,
    sessionId: String,
    projectId: String?,
    directory: String,
    title: String,
    version: String,
    lastOpenedAt: Long,
    updatedAt: Long,
): SessionState {
    return SessionState(
        id = sessionId,
        title = title,
        version = version,
        directory = directory,
        updatedAt = updatedAt,
    )
}

private const val ProjectFavoriteSeparator = "\n"

private fun settingSet(db: AgenticDb, key: String): Set<String> {
    return db.settingsQueries
        .selectSetting(key)
        .executeAsOneOrNull()
        ?.split(ProjectFavoriteSeparator)
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        ?: emptySet()
}

private suspend fun setSettingSet(db: AgenticDb, key: String, value: Set<String>) {
    val next = value
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
    if (next.isEmpty()) {
        db.settingsQueries.deleteSetting(key)
        return
    }
    db.settingsQueries.upsertSetting(key, next.joinToString(ProjectFavoriteSeparator))
}

private fun projectFavoriteKey(server: String): String {
    return "project_favorite:$server"
}

private fun projectHiddenKey(server: String): String {
    return "project_hidden:$server"
}

private fun sessionQuickIncludeKey(server: String): String {
    return "session_quick_include:$server"
}

private fun sessionQuickExcludeKey(server: String): String {
    return "session_quick_exclude:$server"
}
