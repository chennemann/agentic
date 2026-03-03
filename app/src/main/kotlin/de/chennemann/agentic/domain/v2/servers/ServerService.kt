package de.chennemann.agentic.domain.v2.servers

import android.util.Log
import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.UUID

interface ServerService {
    val connectedServer: Flow<ServerInfo>
    val connectionState: Flow<ServerConnectionState>
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
                logInfo("checking for existing servers")
                persistedServers
                .firstOrNull { server -> connect(server.url) }
                ?.also { this.manualConnectedServer.update { it } }
            }
            else -> manualConnectedServer
        } ?: ServerInfo.NONE
    }
    private val _connectionState = MutableStateFlow<ServerConnectionState>(ServerConnectionState.Idle)
    override val connectionState: Flow<ServerConnectionState> = _connectionState.asStateFlow()

    override suspend fun heartbeat() {
        logInfo("heartbeat")
        val server = connectedServer.first() as? ServerInfo.ConnectedServerInfo
        if (server == null) return

        _connectionState.update { ServerConnectionState.Refreshing }

        if (isConnected(server.url)) {
            manualConnectedServer.update { server }
            _connectionState.update { ServerConnectionState.Connected }
            return
        }

        logInfo("heartbeat failed for server '${server.url}'")
        manualConnectedServer.update { ServerInfo.NONE }
        _connectionState.update { ServerConnectionState.Disconnected }
    }

    override suspend fun connect(url: String): Boolean {
        val fallbackState = _connectionState.value
        _connectionState.update { ServerConnectionState.Connecting }

        val baseUrl = normalizeBaseUrl(url) ?: run {
            _connectionState.update { fallbackState }
            return false
        }
        logInfo("check connection to '$baseUrl'")
        if (!isConnected(baseUrl)) {
            _connectionState.update { fallbackState }
            return false
        }
        logInfo("connected to '$baseUrl'")

        val now = System.currentTimeMillis()
        val existing = serverRepository.selectServerByUrl(baseUrl)
        val server = existing?.copy(
            url = baseUrl,
            lastConnectedAt = now,
        ) ?: ServerInfo.ConnectedServerInfo(
            id = UUID.randomUUID().toString(),
            url = baseUrl,
            lastConnectedAt = now,
        )

        if (existing == null) {
            serverRepository.insertServer(server)
        } else {
            serverRepository.updateServer(server)
        }
        manualConnectedServer.update { server }
        _connectionState.update { ServerConnectionState.Connected }

        return true
    }

    private suspend fun isConnected(url: String): Boolean {
        val baseUrl = normalizeBaseUrl(url) ?: return false
        return runCatching {
            adapter.healthCheckWithUrl(baseUrl).healthy
        }.getOrDefault(false)
    }

    private fun logInfo(message: String) {
        Log.i("server-service", message)
    }
}

private fun normalizeBaseUrl(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    val withProtocol = if (trimmed.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*://.*$"))) {
        trimmed
    } else {
        "http://$trimmed"
    }
    return withProtocol.replace(Regex("/+$"), "")
}
