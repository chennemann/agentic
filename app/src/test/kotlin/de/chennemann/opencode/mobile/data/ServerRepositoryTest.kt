package de.chennemann.opencode.mobile.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.async.coroutines.synchronous
import de.chennemann.opencode.mobile.db.AgenticDb
import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.ConnectionState
import de.chennemann.opencode.mobile.domain.session.ConnectivityGateway
import de.chennemann.opencode.mobile.domain.session.LogGateway
import de.chennemann.opencode.mobile.domain.session.LogLevel
import de.chennemann.opencode.mobile.domain.session.LogUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest {
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun projectsCompletesWhileMainPaused() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repo = ServerRepository(
            db = db(),
            mdns = StubMdns(),
            service = StubServer(),
            network = StubNetwork(),
            dispatchers = lanes(main, worker),
            log = StubLog(),
        )

        val load = async { repo.projects() }
        advanceUntilIdle()

        assertTrue(load.isCompleted)
        assertEquals("p1", load.await().first().id)
    }

    @Test
    fun setStreamCursorCompletesWhileMainPaused() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repo = ServerRepository(
            db = db(),
            mdns = StubMdns(),
            service = StubServer(),
            network = StubNetwork(),
            dispatchers = lanes(main, worker),
            log = StubLog(),
        )

        val write = async { repo.setStreamCursor("cursor-1") }
        advanceUntilIdle()
        assertTrue(write.isCompleted)
        write.await()

        val read = async { repo.streamCursor() }
        advanceUntilIdle()
        assertTrue(read.isCompleted)
        assertEquals("cursor-1", read.await())
    }

    @Test
    fun refreshTransitionsThroughSuccessUnhealthyAndRecovery() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubServer().apply {
            healthResults.addLast(Result.success(Health(healthy = true, version = "1.0")))
            healthResults.addLast(Result.success(Health(healthy = false, version = "1.1")))
            healthResults.addLast(Result.success(Health(healthy = true, version = "1.2")))
        }
        val repo = ServerRepository(
            db = db(),
            mdns = StubMdns(),
            service = service,
            network = StubNetwork(),
            dispatchers = lanes(main, worker),
            log = StubLog(),
        )
        val states = mutableListOf<ConnectionState>()
        val collect = launch { repo.status.collect { states += it } }

        val first = async { repo.refresh(true) }
        advanceUntilIdle()
        first.await()

        val second = async { repo.refresh(true) }
        advanceUntilIdle()
        second.await()

        val third = async { repo.refresh(true) }
        advanceUntilIdle()
        third.await()

        collect.cancel()

        assertEquals(
            listOf(
                ConnectionState.Idle,
                ConnectionState.Loading,
                ConnectionState.Connected("1.0"),
                ConnectionState.Loading,
                ConnectionState.Failed("Server is unhealthy"),
                ConnectionState.Loading,
                ConnectionState.Connected("1.2"),
            ),
            states,
        )
    }

    @Test
    fun normalizesSetUrlAndDiscoveredMdnsUrl() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val mdns = StubMdns()
        val repo = ServerRepository(
            db = db(),
            mdns = mdns,
            service = StubServer(),
            network = StubNetwork(),
            dispatchers = lanes(main, worker),
            log = StubLog(),
        )

        val set = async { repo.setUrl("  EXAMPLE.local:8080///  ") }
        advanceUntilIdle()
        set.await()
        assertEquals("http://EXAMPLE.local:8080", repo.endpoint.value)

        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        try {
            repo.start(scope)
            runCurrent()
            mdns.entries.emit(MdnsEntry(name = "opencode-1", host = "demo.local", port = 4096))
            advanceUntilIdle()
            assertEquals("http://demo.local:4096", repo.found.value)
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun streamCursorIsScopedToNormalizedUrl() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repo = ServerRepository(
            db = db(),
            mdns = StubMdns(),
            service = StubServer(),
            network = StubNetwork(),
            dispatchers = lanes(main, worker),
            log = StubLog(),
        )

        repo.setUrl("a.local:4096")
        repo.setStreamCursor("cursor-a")
        assertEquals("cursor-a", repo.streamCursor())

        repo.setUrl(" b.local:4096/ ")
        assertNull(repo.streamCursor())
        repo.setStreamCursor("cursor-b")
        assertEquals("cursor-b", repo.streamCursor())

        repo.setUrl("http://a.local:4096/")
        assertEquals("cursor-a", repo.streamCursor())
    }

    @Test
    fun refreshRunsOnNetworkChangeAfterInitialEmission() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val network = StubNetwork()
        val service = StubServer()
        val repo = ServerRepository(
            db = db(),
            mdns = StubMdns(),
            service = service,
            network = network,
            dispatchers = lanes(main, worker),
            log = StubLog(),
        )

        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        try {
            repo.start(scope)
            advanceUntilIdle()
            assertEquals(1, service.healthCalls.size)

            network.markChanged()
            advanceUntilIdle()

            assertEquals(2, service.healthCalls.size)
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private fun db(): AgenticDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.synchronous().create(driver)
        return AgenticDb(driver)
    }

    private fun lanes(main: TestDispatcher, worker: TestDispatcher): DispatcherProvider {
        return object : DispatcherProvider {
            override val io = worker
            override val default = worker
            override val mainImmediate = main
        }
    }
}

private class StubMdns : MdnsGateway {
    val entries = MutableSharedFlow<MdnsEntry>(extraBufferCapacity = 8)

    override fun discover(): Flow<MdnsEntry> {
        return entries
    }
}

private class StubNetwork : ConnectivityGateway {
    private val onlineState = MutableStateFlow(true)
    private val changedState = MutableStateFlow(0L)

    override val online: StateFlow<Boolean> = onlineState.asStateFlow()
    override val changed: StateFlow<Long> = changedState.asStateFlow()

    fun markChanged() {
        changedState.value += 1
    }
}

private class StubLog : LogGateway {
    override fun log(
        level: LogLevel,
        unit: LogUnit,
        tag: String,
        event: String,
        message: String,
        context: Map<String, String>,
        error: Throwable?,
    ) {
    }
}

private class StubServer : ServerGateway {
    val healthCalls = mutableListOf<String>()
    val healthResults = ArrayDeque<Result<Health>>()

    override suspend fun health(baseUrl: String): Health {
        healthCalls += baseUrl
        val result = healthResults.removeFirstOrNull() ?: Result.success(Health(healthy = true, version = "1"))
        return result.getOrThrow()
    }

    override suspend fun projects(baseUrl: String): List<ProjectInfo> {
        return listOf(
            ProjectInfo(
                id = "p1",
                worktree = "/tmp/p1",
                name = "Project 1",
            )
        )
    }

    override suspend fun sessions(baseUrl: String, worktree: String, limit: Int?): List<SessionInfo> {
        return emptyList()
    }

    override suspend fun archiveSession(baseUrl: String, sessionId: String, directory: String) = Unit

    override suspend fun renameSession(baseUrl: String, sessionId: String, directory: String, title: String) = Unit

    override suspend fun createSession(baseUrl: String, worktree: String, title: String): SessionInfo {
        return SessionInfo(id = "s1", title = title, version = "1", directory = worktree)
    }

    override suspend fun commands(baseUrl: String, directory: String): List<CommandInfo> {
        return emptyList()
    }

    override suspend fun sessionMessages(baseUrl: String, sessionId: String, directory: String, limit: Int?): List<SessionMessageInfo> {
        return emptyList()
    }

    override suspend fun sessionUpdatedAt(baseUrl: String, sessionId: String, directory: String): Long? {
        return null
    }

    override suspend fun sessionStatus(baseUrl: String, directory: String): Map<String, String> {
        return emptyMap()
    }

    override suspend fun streamEvents(
        baseUrl: String,
        lastEventId: String?,
        onRawEvent: suspend (String) -> Unit,
        onEvent: suspend (GlobalStreamEvent) -> Unit,
    ): String? {
        onEvent(
            GlobalStreamEvent(
                directory = "global",
                type = "server.heartbeat",
                properties = JsonObject(emptyMap()),
                id = null,
                retry = null,
            )
        )
        return lastEventId
    }

    override suspend fun sendMessage(baseUrl: String, sessionId: String, directory: String, text: String, agent: String) = Unit

    override suspend fun sendCommand(baseUrl: String, sessionId: String, directory: String, name: String, arguments: String, agent: String) = Unit
}
