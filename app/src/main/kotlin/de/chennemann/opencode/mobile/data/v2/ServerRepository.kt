package de.chennemann.opencode.mobile.data.v2

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import de.chennemann.opencode.mobile.db.AgenticDb
import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.v2.servers.LocalServerInfo
import de.chennemann.opencode.mobile.domain.v2.servers.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SqlDelightServerRepository(
    private val db: AgenticDb,
    private val dispatchers: DispatcherProvider,
) : ServerRepository {
    override fun observeServers(): Flow<List<LocalServerInfo>> {
        return db.serversQueries
            .selectServers(mapper = ::mapServer)
            .asFlow()
            .mapToList(dispatchers.io)
    }

    override suspend fun selectServer(id: String): LocalServerInfo? {
        val serverId = id.trim()
        require(serverId.isNotBlank()) { "id must not be blank" }
        return withContext(dispatchers.io) {
            db.serversQueries
                .selectServerById(serverId, mapper = ::mapServer)
                .executeAsOneOrNull()
        }
    }

    override suspend fun selectServerByUrl(url: String): LocalServerInfo? {
        val serverUrl = url.trim()
        require(serverUrl.isNotBlank()) { "url must not be blank" }
        return withContext(dispatchers.io) {
            db.serversQueries
                .selectServerByUrl(serverUrl, mapper = ::mapServer)
                .executeAsOneOrNull()
        }
    }

    override suspend fun insertServer(server: LocalServerInfo) {
        withContext(dispatchers.io) {
            db.serversQueries.insertServer(
                id = server.id,
                url = server.url,
                last_connected_at = server.lastConnectedAt,
            )
        }
    }

    override suspend fun updateServer(server: LocalServerInfo) {
        withContext(dispatchers.io) {
            db.serversQueries.updateServer(
                url = server.url,
                last_connected_at = server.lastConnectedAt,
                id = server.id,
            )
        }
    }

    override suspend fun deleteServer(id: String) {
        val serverId = id.trim()
        require(serverId.isNotBlank()) { "id must not be blank" }
        withContext(dispatchers.io) {
            db.serversQueries.deleteServer(serverId)
        }
    }
}

private fun mapServer(
    id: String,
    url: String,
    last_connected_at: Long?,
): LocalServerInfo {
    return LocalServerInfo(
        id = id,
        url = url,
        lastConnectedAt = last_connected_at,
    )
}
