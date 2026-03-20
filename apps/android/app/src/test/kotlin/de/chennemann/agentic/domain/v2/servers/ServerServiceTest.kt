package de.chennemann.agentic.domain.v2.servers

import de.chennemann.agentic.domain.v2.fixtures.OpenCodeServerAdapterFixture
import de.chennemann.agentic.domain.v2.fixtures.connectedServerFixture
import de.chennemann.agentic.domain.v2.fixtures.DomainV2TestEnvironment
import de.chennemann.agentic.domain.v2.fixtures.domainV2TestEnvironment
import de.chennemann.agentic.domain.v2.fixtures.healthCheckFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerServiceTest {
    @Test
    fun connectedServerIsNoneInitially() = environmentTest {
        val connected = service.connectedServer.first()

        assertEquals(ServerInfo.NONE, connected)
        assertEquals(ServerConnectionState.Idle, service.connectionState.first())
    }

    @Test
    fun connectedServerIsNoneWhenNoPersistedServersExist() = environmentTest {
        val seeded = seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 1L,
            )
        )
        repository.deleteServer(seeded.id)

        val connected = service.connectedServer.first()

        assertEquals(ServerInfo.NONE, connected)
        assertEquals(ServerConnectionState.Idle, service.connectionState.first())
    }

    @Test
    fun connectedServerIsNoneWhenPersistedServersAreUnreachable() = environmentTest(
        adapter = OpenCodeServerAdapterFixture(
            defaultHealthResult = Result.success(healthCheckFixture(healthy = false)),
        )
    ) {
        seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://unreachable-one.test",
                lastConnectedAt = 20L,
            )
        )
        seedServer(
            connectedServerFixture(
                id = "server-2",
                url = "https://unreachable-two.test",
                lastConnectedAt = 10L,
            )
        )

        val connected = service.connectedServer.first()

        assertEquals(ServerInfo.NONE, connected)
        assertEquals(ServerConnectionState.Idle, service.connectionState.first())
        assertEquals(2, adapter.healthCheckRequests.size)
        assertTrue(adapter.healthCheckRequests.contains("https://unreachable-one.test"))
        assertTrue(adapter.healthCheckRequests.contains("https://unreachable-two.test"))
    }

    @Test
    fun connectedServerIsResetToNoneWhenHeartbeatDetectsUnreachableServer() = environmentTest {
        assertTrue(service.connect("https://example.test"))

        adapter.givenHealthCheck(
            url = "https://example.test",
            result = Result.success(healthCheckFixture(healthy = false)),
        )

        service.heartbeat()

        val server = service.connectedServer.first()

        assertEquals(ServerInfo.NONE, server)
        assertEquals(ServerConnectionState.Disconnected, service.connectionState.first())
        assertEquals(3, adapter.healthCheckRequests.count { it == "https://example.test" })
    }

    @Test
    fun heartbeatDoesNotRetryOnceDisconnected() = environmentTest {
        assertTrue(service.connect("https://example.test"))
        adapter.givenHealthCheck(
            url = "https://example.test",
            result = Result.success(healthCheckFixture(healthy = false)),
        )

        service.heartbeat()

        val beforeExtraHeartbeat = adapter.healthCheckRequests.count { it == "https://example.test" }
        service.heartbeat()
        val afterExtraHeartbeat = adapter.healthCheckRequests.count { it == "https://example.test" }

        assertEquals(ServerConnectionState.Disconnected, service.connectionState.first())
        assertEquals(ServerInfo.NONE, service.connectedServer.first())
        assertTrue(beforeExtraHeartbeat >= 2)
        assertEquals(beforeExtraHeartbeat + 1, afterExtraHeartbeat)
    }

    @Test
    fun connectionStateIsRefreshingWhileHeartbeatRuns() = environmentTest {
        assertTrue(service.connect("https://example.test"))
        adapter.healthCheckDelayMillis = 1_000

        val heartbeatJob = scope.launch {
            service.heartbeat()
        }
        scope.runCurrent()

        assertEquals(ServerConnectionState.Refreshing, service.connectionState.first())

        scope.advanceTimeBy(1_000)
        scope.advanceUntilIdle()
        heartbeatJob.join()

        assertEquals(ServerConnectionState.Connected, service.connectionState.first())
    }

    @Test
    fun connectedServerHasValueWhenConnectWithReachableUrlIsCalled() = environmentTest {
        val connected = service.connect("https://example.test")
        val server = service.connectedServer.first { it is ServerInfo.ConnectedServerInfo } as ServerInfo.ConnectedServerInfo

        assertTrue(connected)
        assertEquals("https://example.test", server.url)
        assertNotNull(server.lastConnectedAt)
        assertEquals(ServerConnectionState.Connected, service.connectionState.first())
    }

    @Test
    fun connectionStateIsConnectingWhileManualConnectRuns() = environmentTest(
        adapter = OpenCodeServerAdapterFixture(healthCheckDelayMillis = 1_000),
    ) {
        val connectJob = scope.launch {
            service.connect("https://example.test")
        }
        scope.runCurrent()

        assertEquals(ServerConnectionState.Connecting, service.connectionState.first())

        scope.advanceTimeBy(1_000)
        scope.advanceUntilIdle()
        connectJob.join()

        assertEquals(ServerConnectionState.Connected, service.connectionState.first())
    }

    @Test
    fun connectedServerUsesReachablePersistedServer() = environmentTest {
        seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://reachable.test",
                lastConnectedAt = 10L,
            )
        )

        val connected = service.connectedServer.first()
        val server = connected as ServerInfo.ConnectedServerInfo

        assertEquals("https://reachable.test", server.url)
        assertEquals(1, adapter.healthCheckRequests.count { it == "https://reachable.test" })
    }

    @Test
    fun connectedServerFlowEmitsConnectedChangeOnlyFromManualConnect() = environmentTest {
        val emissions = mutableListOf<ServerInfo>()
        val collectJob = scope.launch {
            service.connectedServer.collect { emissions += it }
        }

        scope.runCurrent()
        seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 5L,
            )
        )
        service.connect("https://example.test")
        scope.advanceUntilIdle()
        collectJob.cancel()

        val connectedEmissions = emissions.filterIsInstance<ServerInfo.ConnectedServerInfo>()

        assertTrue(connectedEmissions.isNotEmpty())
        assertTrue(adapter.healthCheckRequests.count { it == "https://example.test" } >= 1)
    }

    @Test
    fun connectNormalizesUrlAndAddsHttpProtocolWhenMissing() = environmentTest {
        val connected = service.connect(" example.test:4096/ ")
        val stored = persistedServers().single()

        assertTrue(connected)
        assertEquals(listOf("http://example.test:4096"), adapter.healthCheckRequests)
        assertEquals("http://example.test:4096", stored.url)
        assertNotNull(stored.lastConnectedAt)
    }

    @Test
    fun connectUpdatesExistingPersistedServerByUrl() = environmentTest {
        val seeded = seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 100L,
            )
        )

        assertTrue(service.connect("https://example.test"))

        val persisted = persistedServers().single()
        assertEquals(seeded.id, persisted.id)
        assertEquals(seeded.url, persisted.url)
        assertTrue((persisted.lastConnectedAt ?: 0L) > 100L)
    }

    @Test
    fun removePersistedServerDeletesKnownServer() = environmentTest {
        val seeded = seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 100L,
            )
        )

        val removed = service.removeById(seeded.id)

        assertTrue(removed)
        assertTrue(persistedServers().isEmpty())
    }

    @Test
    fun removePersistedServerReturnsFalseForBlankOrMissingId() = environmentTest {
        seedServer(
            connectedServerFixture(
                id = "server-1",
                url = "https://example.test",
                lastConnectedAt = 100L,
            )
        )

        assertFalse(service.removeById("   "))
        assertFalse(service.removeById("missing"))
        assertEquals(1, persistedServers().size)
    }

    @Test
    fun removePersistedServerResetsConnectedStateWhenRemovingCurrentServer() = environmentTest {
        assertTrue(service.connect("https://example.test"))
        val connected = service.connectedServer.first() as ServerInfo.ConnectedServerInfo

        val removed = service.removeById(connected.id)

        assertTrue(removed)
        assertEquals(ServerInfo.NONE, service.connectedServer.first())
        assertEquals(ServerConnectionState.Idle, service.connectionState.first())
        assertTrue(persistedServers().isEmpty())
    }

    private fun environmentTest(
        adapter: OpenCodeServerAdapterFixture = OpenCodeServerAdapterFixture(),
        testBlock: suspend EnvironmentContext.() -> Unit,
    ) = runTest {
        val environment = domainV2TestEnvironment(
            dispatcher = StandardTestDispatcher(testScheduler),
            adapter = adapter,
        )

        try {
            EnvironmentContext(environment, this).testBlock()
        } finally {
            environment.close()
        }
    }
}

private class EnvironmentContext(
    private val environment: DomainV2TestEnvironment,
    val scope: TestScope,
) {
    val service: DefaultServerService = environment.serverService
    val adapter: OpenCodeServerAdapterFixture = environment.adapter
    val repository: ServerRepository = environment.serverRepository

    suspend fun seedServer(
        server: ServerInfo.ConnectedServerInfo = connectedServerFixture(),
    ): ServerInfo.ConnectedServerInfo {
        return environment.seedServer(server)
    }

    suspend fun persistedServers(): List<ServerInfo.ConnectedServerInfo> {
        return environment.allPersistedServers()
    }
}
