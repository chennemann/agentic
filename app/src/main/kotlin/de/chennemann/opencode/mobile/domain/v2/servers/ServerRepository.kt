package de.chennemann.opencode.mobile.domain.v2.servers

import kotlinx.coroutines.flow.Flow

interface ServerRepository {
    fun observeServers(): Flow<List<LocalServerInfo>>

    suspend fun selectServer(id: String): LocalServerInfo?

    suspend fun selectServerByUrl(url: String): LocalServerInfo?

    suspend fun insertServer(server: LocalServerInfo)

    suspend fun updateServer(server: LocalServerInfo)

    suspend fun deleteServer(id: String)
}
