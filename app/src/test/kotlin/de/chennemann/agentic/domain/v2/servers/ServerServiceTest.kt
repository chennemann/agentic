package de.chennemann.agentic.domain.v2.servers

import de.chennemann.agentic.domain.v2.OpenCodeHealthCheck
import de.chennemann.agentic.domain.v2.OpenCodeProject
import de.chennemann.agentic.domain.v2.OpenCodeServerAdapter
import de.chennemann.agentic.domain.v2.OpenCodeSession
import de.chennemann.agentic.domain.v2.SynchronizationService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerServiceTest {
    @Test
    fun connectReturnsTruePersistsServerAndCallsSyncWhenHealthy() = runTest {
        val adapter = FakeOpenCodeServerAdapter(
            healthCheck = { OpenCodeHealthCheck(healthy = true, version = "1.0.0") },
        )
        val repository = FakeServerRepository()
        val sync = FakeSynchronizationService()
        val service = DefaultServerService(adapter, repository, sync)

        val connected = service.connect("  https://example.test/  ")
        val stored = repository.servers.values.single()

        assertTrue(connected)
        assertEquals("https://example.test", adapter.lastHealthCheckBaseUrl)
        assertEquals("https://example.test", stored.url)
        assertNotNull(stored.lastConnectedAt)
        assertEquals(listOf(stored.id), sync.calls)
    }

    @Test
    fun connectUpdatesExistingServerWhenHealthy() = runTest {
        val adapter = FakeOpenCodeServerAdapter(
            healthCheck = { OpenCodeHealthCheck(healthy = true, version = "1.0.0") },
        )
        val repository = FakeServerRepository()
        val sync = FakeSynchronizationService()
        repository.insertServer(
            LocalServerInfo(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 1L,
            )
        )
        val service = DefaultServerService(adapter, repository, sync)

        val connected = service.connect("https://example.test")
        val stored = repository.servers.values.single()

        assertTrue(connected)
        assertEquals("server-1", stored.id)
        assertTrue((stored.lastConnectedAt ?: 0L) >= 1L)
        assertEquals(listOf("server-1"), sync.calls)
    }

    @Test
    fun connectReturnsFalseWhenServerIsUnhealthy() = runTest {
        val adapter = FakeOpenCodeServerAdapter(
            healthCheck = { OpenCodeHealthCheck(healthy = false, version = "1.0.0") },
        )
        val repository = FakeServerRepository()
        val sync = FakeSynchronizationService()
        val service = DefaultServerService(adapter, repository, sync)

        val connected = service.connect("https://example.test")

        assertFalse(connected)
        assertTrue(repository.servers.isEmpty())
        assertTrue(sync.calls.isEmpty())
    }

    @Test
    fun connectReturnsFalseWhenHealthCheckFails() = runTest {
        val adapter = FakeOpenCodeServerAdapter(
            healthCheck = { throw IllegalStateException("network error") },
        )
        val repository = FakeServerRepository()
        val sync = FakeSynchronizationService()
        val service = DefaultServerService(adapter, repository, sync)

        val connected = service.connect("https://example.test")

        assertFalse(connected)
        assertTrue(repository.servers.isEmpty())
        assertTrue(sync.calls.isEmpty())
    }

    @Test
    fun connectReturnsFalseForBlankUrl() = runTest {
        val adapter = FakeOpenCodeServerAdapter(
            healthCheck = { OpenCodeHealthCheck(healthy = true, version = "1.0.0") },
        )
        val repository = FakeServerRepository()
        val sync = FakeSynchronizationService()
        val service = DefaultServerService(adapter, repository, sync)

        val connected = service.connect("   ")

        assertFalse(connected)
        assertTrue(repository.servers.isEmpty())
        assertTrue(sync.calls.isEmpty())
        assertEquals(null, adapter.lastHealthCheckBaseUrl)
    }
}

private class FakeOpenCodeServerAdapter(
    private val healthCheck: suspend (String) -> OpenCodeHealthCheck,
) : OpenCodeServerAdapter {
    var lastHealthCheckBaseUrl: String? = null

    override suspend fun healthCheckWithUrl(baseUrl: String): OpenCodeHealthCheck {
        lastHealthCheckBaseUrl = baseUrl
        return healthCheck(baseUrl)
    }

    override suspend fun allProjects(baseUrl: String): List<OpenCodeProject> = emptyList()

    override suspend fun allSessionsOfAGivenProject(baseUrl: String, path: String): List<OpenCodeSession> = emptyList()
}

private class FakeServerRepository : ServerRepository {
    val servers = linkedMapOf<String, LocalServerInfo>()

    override fun observeServers(): Flow<List<LocalServerInfo>> {
        return flowOf(servers.values.toList())
    }

    override suspend fun selectServer(id: String): LocalServerInfo? {
        return servers[id]
    }

    override suspend fun selectServerByUrl(url: String): LocalServerInfo? {
        return servers.values.firstOrNull { it.url == url }
    }

    override suspend fun insertServer(server: LocalServerInfo) {
        servers[server.id] = server
    }

    override suspend fun updateServer(server: LocalServerInfo) {
        servers[server.id] = server
    }

    override suspend fun deleteServer(id: String) {
        servers.remove(id)
    }
}

private class FakeSynchronizationService : SynchronizationService {
    val calls = mutableListOf<String>()

    override suspend fun syncServer(serverId: String) {
        calls += serverId
    }
}
