package de.chennemann.agentic.data.v2

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.v2.servers.ServerInfo
import de.chennemann.agentic.domain.v2.servers.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SqlDelightServerRepository(
    private val db: AgenticDb,
    private val dispatchers: DispatcherProvider,
) : ServerRepository {
    override fun observeServers(): Flow<List<ServerInfo.ConnectedServerInfo>> {
        return db.serversQueries
            .selectServers(mapper = ::mapServer)
            .asFlow()
            .mapToList(dispatchers.io)
    }

    override suspend fun selectServer(id: String): ServerInfo.ConnectedServerInfo? {
        val serverId = id.trim()
        require(serverId.isNotBlank()) { "id must not be blank" }
        return withContext(dispatchers.io) {
            db.serversQueries
                .selectServerById(serverId, mapper = ::mapServer)
                .executeAsOneOrNull()
        }
    }

    override suspend fun selectServerByUrl(url: String): ServerInfo.ConnectedServerInfo? {
        val serverUrl = url.trim()
        require(serverUrl.isNotBlank()) { "url must not be blank" }
        return withContext(dispatchers.io) {
            db.serversQueries
                .selectServerByUrl(serverUrl, mapper = ::mapServer)
                .executeAsOneOrNull()
        }
    }

    override suspend fun insertServer(server: ServerInfo.ConnectedServerInfo) {
        withContext(dispatchers.io) {
            db.serversQueries.insertServer(
                id = server.id,
                url = server.url,
                last_connected_at = server.lastConnectedAt,
            )
        }
    }

    override suspend fun updateServer(server: ServerInfo.ConnectedServerInfo) {
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
): ServerInfo.ConnectedServerInfo {
    return ServerInfo.ConnectedServerInfo(
        id = id,
        url = url,
        lastConnectedAt = last_connected_at,
    )
}
