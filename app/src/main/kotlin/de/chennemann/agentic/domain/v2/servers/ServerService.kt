package de.chennemann.agentic.domain.v2.servers

import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
            ServerInfo.NONE -> {
                restorePersistedConnection(persistedServers)
            }
            else -> manualConnectedServer
        }
    }.distinctUntilChanged()

    override suspend fun heartbeat() {
        logInfo("heartbeat")
        when (val server = connectedServer.first()) {
            is ServerInfo.ConnectedServerInfo if (!isConnected(server.url)) -> {
                logInfo("reset connection to server '${server.url}")
                manualConnectedServer.update { ServerInfo.NONE }
            }
            else -> { /* ignore */ }
        }
    }

    override suspend fun connect(url: String): Boolean {
        val baseUrl = normalizeBaseUrl(url) ?: return false
        logInfo("check connection to '$baseUrl'")
        if (!isConnected(baseUrl)) return false
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

        return true
    }

    private suspend fun isConnected(url: String): Boolean {
        val baseUrl = normalizeBaseUrl(url) ?: return false
        return runCatching {
            adapter.healthCheckWithUrl(baseUrl).healthy
        }.getOrDefault(false)
    }

    private suspend fun restorePersistedConnection(servers: List<ServerInfo.ConnectedServerInfo>): ServerInfo {
        logInfo("checking for existing servers")

        val connected = servers
            .sortedByDescending { it.lastConnectedAt ?: Long.MIN_VALUE }
            .firstOrNull { server -> isConnected(server.url) }
            ?: return ServerInfo.NONE

        val updated = connected.copy(lastConnectedAt = System.currentTimeMillis())
        serverRepository.updateServer(updated)
        manualConnectedServer.update { updated }
        return updated
    }

    @Suppress("UNUSED_PARAMETER")
    private fun logInfo(message: String) = Unit
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
