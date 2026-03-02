package de.chennemann.agentic.domain.v2.servers

import android.util.Log
import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import de.chennemann.agentic.domain.v2.SynchronizationService
import de.chennemann.agentic.domain.v2.projects.LocalProjectInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import java.util.UUID

interface ServerService {
    fun connectedServers(): Flow<List<LocalServerInfo>>
    suspend fun connect(url: String): Boolean
}

class DefaultServerService(
    private val adapter: OpenCodeServerAdapter,
    private val serverRepository: ServerRepository,
    private val synchronizationService: SynchronizationService,
) : ServerService {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun connectedServers(): Flow<List<LocalServerInfo>> {
        Log.i("server-service", "Load connected servers")
        return serverRepository
            .observeServers()
            .mapLatest { servers ->
                servers.filter { server ->
                    val connected = connect(server.url)
                    Log.i("server-service", "${server.url} is connected")
                    connected
                }
            }
    }
    override suspend fun connect(url: String): Boolean {
        val baseUrl = normalizeBaseUrl(url) ?: return false
        val healthy = runCatching {
            adapter.healthCheckWithUrl(baseUrl).healthy
        }.getOrDefault(false)
        if (!healthy) return false

        val now = System.currentTimeMillis()
        val existing = serverRepository.selectServerByUrl(baseUrl)
        val server = if (existing == null) {
            LocalServerInfo(
                id = UUID.randomUUID().toString(),
                url = baseUrl,
                lastConnectedAt = now,
            )
        } else {
            existing.copy(
                url = baseUrl,
                lastConnectedAt = now,
            )
        }

        if (existing == null) {
            serverRepository.insertServer(server)
        } else {
            serverRepository.updateServer(server)
        }
        synchronizationService.syncServer(server.id)
        return true
    }
}

private fun normalizeBaseUrl(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    return trimmed.replace(Regex("/+$"), "")
}
