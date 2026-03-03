package de.chennemann.agentic.domain.v2.servers

import de.chennemann.agentic.domain.v2.fixtures.OpenCodeServerAdapterFixture
import de.chennemann.agentic.domain.v2.fixtures.ServerServiceTestEnvironment
import de.chennemann.agentic.domain.v2.fixtures.connectedServerFixture
import de.chennemann.agentic.domain.v2.fixtures.healthCheckFixture
import de.chennemann.agentic.domain.v2.fixtures.serverServiceTestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerServiceTest {
    @Test
    fun connectedServerIsNoneInitially() = environmentTest {
        val connected = service.connectedServer.first()

        assertEquals(ServerInfo.NONE, connected)
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
        assertEquals(
            listOf(
                "https://unreachable-one.test",
                "https://unreachable-two.test",
            ),
            adapter.healthCheckRequests,
        )
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
    }

    @Test
    fun connectedServerHasValueWhenConnectWithReachableUrlIsCalled() = environmentTest {
        val connected = service.connect("https://example.test")
        val server = service.connectedServer.first { it is ServerInfo.ConnectedServerInfo } as ServerInfo.ConnectedServerInfo

        assertTrue(connected)
        assertEquals("https://example.test", server.url)
        assertNotNull(server.lastConnectedAt)
    }

    @Test
    fun connectedServerHasValueWhenOnePersistedServerIsReachable() = environmentTest(
        adapter = OpenCodeServerAdapterFixture(
            defaultHealthResult = Result.success(healthCheckFixture(healthy = false)),
        )
    ) {
        val unreachable = connectedServerFixture(
            id = "server-1",
            url = "https://unreachable.test",
            lastConnectedAt = 20L,
        )
        val reachable = connectedServerFixture(
            id = "server-2",
            url = "https://reachable.test",
            lastConnectedAt = 10L,
        )
        seedServer(unreachable)
        seedServer(reachable)
        adapter.givenHealthCheck(
            url = "https://reachable.test",
            result = Result.success(healthCheckFixture(healthy = true)),
        )

        val connected = service.connectedServer.first { it is ServerInfo.ConnectedServerInfo } as ServerInfo.ConnectedServerInfo

        assertEquals(reachable.id, connected.id)
        assertEquals(reachable.url, connected.url)
    }

    @Test
    fun connectedServerFlowEmitsConnectedChangeOnlyOnce() = environmentTest {
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
        scope.advanceUntilIdle()
        collectJob.cancel()

        val connectedEmissions = emissions.filterIsInstance<ServerInfo.ConnectedServerInfo>()

        assertEquals(1, connectedEmissions.size)
        assertEquals(1, adapter.healthCheckRequests.count { it == "https://example.test" })
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
    fun restoreUpdatesLastConnectedAtOnlyForSelectedServer() = environmentTest {
        val selected = seedServer(
            connectedServerFixture(
                id = "server-selected",
                url = "https://selected.test",
                lastConnectedAt = 100L,
            )
        )
        val untouched = seedServer(
            connectedServerFixture(
                id = "server-untouched",
                url = "https://untouched.test",
                lastConnectedAt = 90L,
            )
        )

        val connected = service.connectedServer.first { it is ServerInfo.ConnectedServerInfo } as ServerInfo.ConnectedServerInfo
        val persistedById = persistedServers().associateBy { it.id }

        assertEquals(selected.id, connected.id)
        assertTrue((persistedById.getValue(selected.id).lastConnectedAt ?: 0L) > 100L)
        assertEquals(untouched.lastConnectedAt, persistedById.getValue(untouched.id).lastConnectedAt)
    }

    @Test
    fun restoreChecksServersByLastConnectedAtDescending() = environmentTest(
        adapter = OpenCodeServerAdapterFixture(
            defaultHealthResult = Result.success(healthCheckFixture(healthy = false)),
        )
    ) {
        seedServer(
            connectedServerFixture(
                id = "server-middle-reachable",
                url = "https://middle-reachable.test",
                lastConnectedAt = 20L,
            )
        )
        seedServer(
            connectedServerFixture(
                id = "server-oldest-reachable",
                url = "https://oldest-reachable.test",
                lastConnectedAt = 10L,
            )
        )
        seedServer(
            connectedServerFixture(
                id = "server-newest-unreachable",
                url = "https://newest-unreachable.test",
                lastConnectedAt = 30L,
            )
        )
        adapter.givenHealthCheck(
            url = "https://middle-reachable.test",
            result = Result.success(healthCheckFixture(healthy = true)),
        )
        adapter.givenHealthCheck(
            url = "https://oldest-reachable.test",
            result = Result.success(healthCheckFixture(healthy = true)),
        )

        val connected = service.connectedServer.first { it is ServerInfo.ConnectedServerInfo } as ServerInfo.ConnectedServerInfo

        assertEquals("server-middle-reachable", connected.id)
        assertEquals(
            listOf(
                "https://newest-unreachable.test",
                "https://middle-reachable.test",
            ),
            adapter.healthCheckRequests,
        )
    }

    private fun environmentTest(
        adapter: OpenCodeServerAdapterFixture = OpenCodeServerAdapterFixture(),
        testBlock: suspend EnvironmentContext.() -> Unit,
    ) = runTest {
        val environment = serverServiceTestEnvironment(
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
    private val environment: ServerServiceTestEnvironment,
    val scope: TestScope,
) {
    val service: DefaultServerService = environment.service
    val adapter: OpenCodeServerAdapterFixture = environment.adapter
    val repository: ServerRepository = environment.repository

    suspend fun seedServer(
        server: ServerInfo.ConnectedServerInfo = connectedServerFixture(),
    ): ServerInfo.ConnectedServerInfo {
        return environment.seedServer(server)
    }

    suspend fun persistedServers(): List<ServerInfo.ConnectedServerInfo> {
        return environment.allPersistedServers()
    }
}
