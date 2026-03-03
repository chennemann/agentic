package de.chennemann.agentic.domain.v2.servers

import android.util.Log
import de.chennemann.agentic.domain.v2.OpenCodeHealthCheck
import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlin.collections.firstOrNull

interface ServerService {
    val connectedServer: Flow<ServerInfo>
    suspend fun connect(url: String): Boolean
    suspend fun heartbeat()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultServerService(
    private val adapter: OpenCodeServerAdapter,
    private val serverRepository: ServerRepository,
) : ServerService {

    private val persistedServers = serverRepository.observeServers()
    private val manualConnectedServer: MutableStateFlow<ServerInfo> = MutableStateFlow(ServerInfo.NONE)

    override val connectedServer: Flow<ServerInfo> = combine(manualConnectedServer, persistedServers) { manualConnectedServer, persistedServers ->
        when (manualConnectedServer) {
            is ServerInfo.NONE -> {
                Log.i("server-service", "checking for existing servers")
                persistedServers
                .firstOrNull { server -> connect(server.url) }
                ?.also { this.manualConnectedServer.update { it } }
            }
            else -> manualConnectedServer
        } ?: ServerInfo.NONE
    }

    override suspend fun heartbeat() {
        Log.i("server-service", "heartbeat")
        when (val server = connectedServer.last()) {
            is ServerInfo.ConnectedServerInfo if (!isConnected(server.url)) -> {
                Log.i("server-service", "reset connection to server '${server.url}")
                manualConnectedServer.update { ServerInfo.NONE }
            }
            else -> { /* ignore */ }
        }
    }

    override suspend fun connect(url: String): Boolean {
        Log.i("server-service", "check connection to '$url'")
        if (!isConnected(url)) return false
        Log.i("server-service", "connected to '$url'")

        val now = System.currentTimeMillis()
        val existing = serverRepository.selectServerByUrl(url)
        val server = existing?.copy(
            url = url,
            lastConnectedAt = now,
        ) ?: ServerInfo.ConnectedServerInfo(
            id = UUID.randomUUID().toString(),
            url = url,
            lastConnectedAt = now,
        )

        if (existing == null) {
            serverRepository.insertServer(server)
        } else {
            serverRepository.updateServer(server)
        }

        return true
    }

    private suspend fun isConnected(url: String): Boolean {
        val baseUrl = normalizeBaseUrl(url) ?: return false
        return adapter.healthCheckWithUrl(baseUrl).healthy
    }
}

private fun normalizeBaseUrl(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    return trimmed.replace(Regex("/+$"), "")
}
