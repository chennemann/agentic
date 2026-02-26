package de.chennemann.opencode.mobile.domain.v2.servers

import de.chennemann.opencode.mobile.domain.v2.OpenCodeServerAdapter
import de.chennemann.opencode.mobile.domain.v2.SynchronizationService
import java.util.UUID

interface ServerService {
    suspend fun connect(url: String): Boolean
}

class DefaultServerService(
    private val adapter: OpenCodeServerAdapter,
    private val serverRepository: ServerRepository,
    private val synchronizationService: SynchronizationService,
) : ServerService {
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
