package de.chennemann.agentic.domain.v2.servers

import kotlinx.coroutines.flow.Flow

interface ServerRepository {
    fun observeServers(): Flow<List<ServerInfo.ConnectedServerInfo>>

    suspend fun selectServer(id: String): ServerInfo.ConnectedServerInfo?

    suspend fun selectServerByUrl(url: String): ServerInfo.ConnectedServerInfo?

    suspend fun insertServer(server: ServerInfo.ConnectedServerInfo)

    suspend fun updateServer(server: ServerInfo.ConnectedServerInfo)

    suspend fun deleteServer(id: String)
}
