package de.chennemann.agentic.domain.connection

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.cache.SqlCommandOutbox
import de.chennemann.agentic.data.t3.EnvironmentMetadataClient
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.data.t3.T3TransportException
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.DurableCommandDispatcher
import de.chennemann.agentic.domain.orchestration.OutboxCommandStatus
import de.chennemann.agentic.domain.orchestration.PendingCommandReplayer
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.EnvironmentPlatform
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentCapabilities
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetail
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ServerAuthDescriptor
import de.chennemann.agentic.t3.contract.ThreadSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSupervisorTest {
    @Test
    fun `overlapping live retries collapse into one replay`() = runTest {
        val environment = savedEnvironment("one")
        val transport = SupervisorTransport()
        val releaseRetry = CompletableDeferred<Unit>()
        var replayCount = 0
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(environment),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = PendingCommandReplayer {
                replayCount += 1
                if (replayCount > 1) releaseRetry.await()
            },
            scope = backgroundScope,
        )
        runCurrent()

        supervisor.retryPendingCommands()
        supervisor.retryPendingCommands()
        runCurrent()

        assertEquals(2, replayCount)
        releaseRetry.complete(Unit)
    }

    @Test
    fun `live retry redispatches persisted failure and removes it only after acknowledgement`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val environment = savedEnvironment("one")
        val environments = SupervisorEnvironmentRepository(environment)
        val outbox = SqlCommandOutbox(AgenticDb(driver), StandardTestDispatcher(testScheduler))
        val command = ClientOrchestrationCommand.ArchiveThread("persisted-command", "thread-one")
        outbox.enqueue(environment.id, command)
        val commandClient = RetryCommandClient(failing = true)
        val dispatcher = DurableCommandDispatcher(
            environments = environments,
            credentials = SupervisorCredentialStore("token"),
            client = commandClient,
            outbox = outbox,
        )
        val transport = SupervisorTransport()
        val supervisor = ConnectionSupervisor(
            environments = environments,
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = dispatcher,
            scope = backgroundScope,
        )
        runCurrent()

        assertEquals(ConnectionState.Live, supervisor.state.value)
        assertEquals(OutboxCommandStatus.FAILED, outbox.commands(environment.id).single().status)
        assertEquals(listOf("persisted-command"), commandClient.commandIds)

        commandClient.failing = false
        supervisor.retryPendingCommands()
        runCurrent()

        assertEquals(listOf("persisted-command", "persisted-command"), commandClient.commandIds)
        assertTrue(outbox.commands(environment.id).isEmpty())
        driver.close()
    }

    @Test
    fun `retry pending commands replays while connection is already live`() = runTest {
        val environment = savedEnvironment("one")
        val transport = SupervisorTransport()
        val replayed = mutableListOf<String>()
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(environment),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = PendingCommandReplayer { replayed += it.id },
            scope = backgroundScope,
        )
        runCurrent()
        assertEquals(ConnectionState.Live, supervisor.state.value)
        assertEquals(listOf("one"), replayed)

        supervisor.retryPendingCommands()
        runCurrent()

        assertEquals(listOf("one", "one"), replayed)
    }

    @Test
    fun `pending commands replay after the live shell snapshot is refreshed`() = runTest {
        val environment = savedEnvironment("one")
        val transport = SupervisorTransport()
        val replayedEnvironmentIds = mutableListOf<String>()

        ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(environment),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = PendingCommandReplayer { replayedEnvironmentIds += it.id },
            scope = backgroundScope,
        )
        runCurrent()

        assertEquals(listOf(environment.id), replayedEnvironmentIds)
    }

    @Test
    fun `foreground wake cancels a stuck connection and starts fresh`() = runTest {
        val environment = savedEnvironment("one")
        val transport = SupervisorTransport(blockFirstDescriptor = true)
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(environment),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()

        assertEquals(ConnectionState.Connecting, supervisor.state.value)
        assertEquals(1, transport.descriptorRequests)

        supervisor.wake()
        runCurrent()

        assertEquals(2, transport.descriptorRequests)
        assertEquals(1, transport.cancelledDescriptorRequests)
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `manual retry immediately leaves backoff and starts fresh`() = runTest {
        val transport = SupervisorTransport(failFirstDescriptor = true)
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(savedEnvironment("one")),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()

        assertTrue(supervisor.state.value is ConnectionState.Backoff)
        assertEquals(1, transport.descriptorRequests)

        supervisor.wake()

        assertEquals(ConnectionState.Connecting, supervisor.state.value)
        runCurrent()
        assertEquals(2, transport.descriptorRequests)
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `explicit reconnect preserves the latest failure while retrying`() = runTest {
        val transport = SupervisorTransport(failFirstDescriptor = true)
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(savedEnvironment("one")),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()
        val failure = supervisor.state.value as ConnectionState.Backoff

        supervisor.reconnect()

        assertEquals(failure.message, (supervisor.state.value as ConnectionState.Backoff).message)
        runCurrent()
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `network restoration cancels old streams and reconnects immediately`() = runTest {
        val environment = savedEnvironment("one")
        val network = MutableOnlineMonitor(true)
        val transport = SupervisorTransport()
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(environment),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = network,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()

        network.mutableOnline.value = false
        runCurrent()
        network.mutableOnline.value = true
        runCurrent()

        assertEquals(2, transport.descriptorRequests)
        assertTrue(transport.cancelledStreams >= 1)
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `foreground wake keeps an already live connection`() = runTest {
        val transport = SupervisorTransport()
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(savedEnvironment("one")),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()

        supervisor.wake()
        runCurrent()

        assertEquals(1, transport.descriptorRequests)
        assertEquals(0, transport.cancelledStreams)
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `completed shell stream resumes without a full reconnect`() = runTest {
        val transport = SupervisorTransport(completeFirstShellStream = true)
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(savedEnvironment("one")),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertEquals(1, transport.descriptorRequests)
        assertEquals(2, transport.shellStreamRequests)
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `transient shell failure resumes without a full reconnect`() = runTest {
        val transport = SupervisorTransport(failFirstShellStream = true)
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(savedEnvironment("one")),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, transport.descriptorRequests)
        assertEquals(2, transport.shellStreamRequests)
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `completed focused thread stream resumes without dropping shell connection`() = runTest {
        val transport = SupervisorTransport(completeFirstThreadStream = true)
        val orchestration = SupervisorOrchestrationRepository()
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(savedEnvironment("one")),
            orchestration = orchestration,
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()
        orchestration.focusedThreadId.value = "thread-one"
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertEquals(1, transport.descriptorRequests)
        assertEquals(1, transport.threadRequests.size)
        assertEquals(2, transport.threadStreamRequests)
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `transient focused thread failure retries without dropping shell connection`() = runTest {
        val transport = SupervisorTransport(failFirstThreadStream = true)
        val orchestration = SupervisorOrchestrationRepository()
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(savedEnvironment("one")),
            orchestration = orchestration,
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()
        orchestration.focusedThreadId.value = "thread-one"
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, transport.descriptorRequests)
        assertEquals(1, transport.threadRequests.size)
        assertEquals(2, transport.threadStreamRequests)
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `authentication failure blocks instead of retrying`() = runTest {
        val environment = savedEnvironment("one")
        val repository = SupervisorEnvironmentRepository(environment)
        val orchestration = SupervisorOrchestrationRepository()
        val transport = SupervisorTransport()
        val supervisor = ConnectionSupervisor(
            environments = repository,
            orchestration = orchestration,
            credentials = SupervisorCredentialStore(token = null),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )

        runCurrent()

        assertTrue(supervisor.state.value is ConnectionState.BlockedAuthentication)
        assertEquals(0, transport.descriptorRequests)
    }

    @Test
    fun `focused thread and environment switches cancel old streams`() = runTest {
        val first = savedEnvironment("one")
        val environments = SupervisorEnvironmentRepository(first)
        val orchestration = SupervisorOrchestrationRepository()
        val transport = SupervisorTransport()
        val supervisor = ConnectionSupervisor(
            environments = environments,
            orchestration = orchestration,
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )
        runCurrent()
        orchestration.focusedThreadId.value = "thread-one"
        runCurrent()
        orchestration.focusedThreadId.value = "thread-two"
        runCurrent()
        environments.mutableActive.value = savedEnvironment("two")
        environments.mutableEnvironments.value = listOf(environments.mutableActive.value!!)
        runCurrent()

        assertTrue(transport.threadRequests.containsAll(listOf("thread-one", "thread-two")))
        assertEquals("https://two.example.test/", transport.shellBaseUrls.last())
        assertTrue(transport.cancelledStreams >= 2)
        assertEquals(ConnectionState.Live, supervisor.state.value)
    }

    @Test
    fun `active thread progress synchronizes before the thread is focused`() = runTest {
        val activeThread = threadShell("thread-active", "running")
        val idleThread = threadShell("thread-idle", null)
        val transport = SupervisorTransport(shellThreads = listOf(activeThread, idleThread))
        ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(savedEnvironment("one")),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            rpc = transport,
            network = OnlineMonitor,
            pendingCommands = NoOpPendingCommandReplayer,
            scope = backgroundScope,
        )

        runCurrent()

        assertTrue("thread-active" in transport.threadRequests)
        assertTrue("thread-idle" !in transport.threadRequests)
    }
}

private val NoOpPendingCommandReplayer = PendingCommandReplayer { }

private class RetryCommandClient(
    var failing: Boolean,
) : T3RpcClient {
    val commandIds = mutableListOf<String>()

    override suspend fun dispatch(
        baseUrl: String,
        bearerToken: String,
        command: ClientOrchestrationCommand,
    ): DispatchResult {
        commandIds += command.commandId
        if (failing) error("offline")
        return DispatchResult(1)
    }

    override suspend fun serverConfig(baseUrl: String, bearerToken: String): ServerConfig =
        error("Not used by this test")

    override fun shellStream(
        baseUrl: String,
        bearerToken: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationShellStreamItem> = flowOf()

    override fun threadStream(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationThreadStreamItem> = flowOf()
}

private object OnlineMonitor : NetworkMonitor {
    override val online: StateFlow<Boolean> = MutableStateFlow(true)
}

private class MutableOnlineMonitor(
    initial: Boolean,
) : NetworkMonitor {
    val mutableOnline = MutableStateFlow(initial)
    override val online: StateFlow<Boolean> = mutableOnline
}

private class SupervisorCredentialStore(
    private val token: String?,
) : CredentialStore {
    override suspend fun read(environmentId: String): String? = token

    override suspend fun write(
        environmentId: String,
        bearerToken: String,
    ) = Unit

    override suspend fun remove(environmentId: String) = Unit
}

private class SupervisorEnvironmentRepository(
    initial: SavedEnvironment,
) : EnvironmentRepository {
    val mutableEnvironments = MutableStateFlow(listOf(initial))
    val mutableActive = MutableStateFlow<SavedEnvironment?>(initial)
    override val environments: StateFlow<List<SavedEnvironment>> = mutableEnvironments
    override val activeEnvironment: StateFlow<SavedEnvironment?> = mutableActive

    override suspend fun save(
        baseUrl: String,
        descriptor: ExecutionEnvironmentDescriptor,
        makeActive: Boolean,
    ) = Unit

    override suspend fun select(environmentId: String) = Unit

    override suspend fun remove(environmentId: String) = Unit

    override suspend fun markConnected(
        environmentId: String,
        connectedAt: Long,
    ) = Unit
}

private class SupervisorTransport(
    private val blockFirstDescriptor: Boolean = false,
    private val failFirstDescriptor: Boolean = false,
    private val completeFirstShellStream: Boolean = false,
    private val failFirstShellStream: Boolean = false,
    private val completeFirstThreadStream: Boolean = false,
    private val failFirstThreadStream: Boolean = false,
    private val shellThreads: List<OrchestrationThreadShell> = emptyList(),
) : EnvironmentMetadataClient,
    T3RpcClient {
    var descriptorRequests = 0
    var cancelledDescriptorRequests = 0
    val shellBaseUrls = mutableListOf<String>()
    val threadRequests = mutableListOf<String>()
    var shellStreamRequests = 0
    var threadStreamRequests = 0
    var cancelledStreams = 0

    override suspend fun environmentDescriptor(baseUrl: String): ExecutionEnvironmentDescriptor {
        descriptorRequests++
        if (blockFirstDescriptor && descriptorRequests == 1) {
            try {
                awaitCancellation()
            } finally {
                cancelledDescriptorRequests++
            }
        }
        if (failFirstDescriptor && descriptorRequests == 1) {
            error("Environment unavailable")
        }
        return descriptor(baseUrl.substringAfter("https://").substringBefore('.'))
    }

    override suspend fun serverConfig(
        baseUrl: String,
        bearerToken: String,
    ): ServerConfig = ServerConfig(
        environment = descriptor(baseUrl.substringAfter("https://").substringBefore('.')),
        auth = ServerAuthDescriptor(
            policy = "remote-reachable",
            bootstrapMethods = listOf("one-time-token"),
            sessionMethods = listOf("bearer-access-token"),
            sessionCookieName = "t3-session",
        ),
        providers = emptyList(),
        shellResumeCompletionMarker = true,
        threadResumeCompletionMarker = true,
    )

    override suspend fun dispatch(
        baseUrl: String,
        bearerToken: String,
        command: ClientOrchestrationCommand,
    ) = DispatchResult(1)

    override fun shellStream(
        baseUrl: String,
        bearerToken: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationShellStreamItem> {
        shellStreamRequests++
        shellBaseUrls += baseUrl
        val snapshot = OrchestrationShellStreamItem.Snapshot(
            OrchestrationShellSnapshot(emptyList(), shellThreads, 0, "2026-01-01T00:00:00Z"),
        )
        return when {
            completeFirstShellStream && shellStreamRequests == 1 ->
                flowOf(snapshot, OrchestrationShellStreamItem.Synchronized)

            failFirstShellStream && shellStreamRequests == 1 -> flow {
                emit(snapshot)
                emit(OrchestrationShellStreamItem.Synchronized)
                throw T3TransportException.Network()
            }

            else -> cancellableFlow(snapshot, OrchestrationShellStreamItem.Synchronized)
        }
    }

    override fun threadStream(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        afterSequence: Long?,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationThreadStreamItem> {
        threadStreamRequests++
        if (afterSequence == null) threadRequests += threadId
        val snapshot = OrchestrationThreadStreamItem.Snapshot(threadSnapshot(threadId))
        return when {
            completeFirstThreadStream && threadStreamRequests == 1 ->
                flowOf(snapshot, OrchestrationThreadStreamItem.Synchronized)

            failFirstThreadStream && threadStreamRequests == 1 -> flow {
                emit(snapshot)
                emit(OrchestrationThreadStreamItem.Synchronized)
                throw T3TransportException.Network()
            }

            else -> cancellableFlow(snapshot, OrchestrationThreadStreamItem.Synchronized)
        }
    }

    private fun <T> cancellableFlow(vararg items: T): Flow<T> = flow {
        try {
            items.forEach { emit(it) }
            awaitCancellation()
        } finally {
            cancelledStreams++
        }
    }
}

private class SupervisorOrchestrationRepository : OrchestrationRepository {
    override val clientConfig = MutableStateFlow(ProjectionState<ServerConfig>())
    override val shell = MutableStateFlow(ProjectionState<OrchestrationShellSnapshot>())
    override val focusedThread = MutableStateFlow(ProjectionState<OrchestrationThreadDetailSnapshot>())
    override val focusedThreadId = MutableStateFlow<String?>(null)
    override val selectedProjectId = MutableStateFlow<String?>(null)

    override suspend fun loadCached(environmentId: String) = Unit

    override suspend fun setClientConfig(
        environmentId: String,
        config: ServerConfig,
        source: ProjectionSource,
    ) {
        clientConfig.value = ProjectionState(config, source = source)
    }

    override suspend fun setShellSnapshot(
        environmentId: String,
        snapshot: OrchestrationShellSnapshot,
        source: ProjectionSource,
    ) {
        shell.value = ProjectionState(snapshot, snapshot.snapshotSequence, source)
    }

    override suspend fun applyShellItem(
        environmentId: String,
        item: OrchestrationShellStreamItem,
    ): Reduction<ProjectionState<OrchestrationShellSnapshot>> {
        shell.value = when (item) {
            is OrchestrationShellStreamItem.Snapshot -> ProjectionState(
                value = item.snapshot,
                sequence = item.snapshot.snapshotSequence,
                source = ProjectionSource.LIVE,
            )

            OrchestrationShellStreamItem.Synchronized -> shell.value.copy(synchronized = true)
            else -> shell.value
        }
        return Reduction.Applied(shell.value)
    }

    override suspend fun focusThread(
        environmentId: String,
        threadId: String?,
    ) {
        focusedThreadId.value = threadId
    }

    override suspend fun selectProject(
        environmentId: String,
        projectId: String?,
    ) {
        selectedProjectId.value = projectId
    }

    override suspend fun setThreadSnapshot(
        environmentId: String,
        snapshot: OrchestrationThreadDetailSnapshot,
        source: ProjectionSource,
    ) {
        focusedThread.value = ProjectionState(snapshot, snapshot.snapshotSequence, source)
    }

    override suspend fun applyThreadItem(
        environmentId: String,
        threadId: String,
        item: OrchestrationThreadStreamItem,
    ): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>> {
        focusedThread.value = when (item) {
            is OrchestrationThreadStreamItem.Snapshot -> ProjectionState(
                value = item.snapshot,
                sequence = item.snapshot.snapshotSequence,
                source = ProjectionSource.LIVE,
            )

            OrchestrationThreadStreamItem.Synchronized -> focusedThread.value.copy(synchronized = true)
            else -> focusedThread.value
        }
        return Reduction.Applied(focusedThread.value)
    }

    override suspend fun clearEnvironment(environmentId: String) = Unit
}

private fun savedEnvironment(id: String): SavedEnvironment = SavedEnvironment(
    id = id,
    label = id,
    baseUrl = "https://$id.example.test/",
    platformOs = "linux",
    platformArch = "x64",
    serverVersion = "1",
    active = true,
    lastConnectedAt = null,
)

private fun descriptor(id: String): ExecutionEnvironmentDescriptor = ExecutionEnvironmentDescriptor(
    environmentId = id,
    label = id,
    platform = EnvironmentPlatform("linux", "x64"),
    serverVersion = "1",
    capabilities = ExecutionEnvironmentCapabilities(),
)

private fun threadSnapshot(threadId: String): OrchestrationThreadDetailSnapshot =
    OrchestrationThreadDetailSnapshot(
        snapshotSequence = 0,
        thread = OrchestrationThreadDetail(
            id = threadId,
            projectId = "project",
            title = threadId,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        ),
    )

private fun threadShell(
    threadId: String,
    status: String?,
) = OrchestrationThreadShell(
    id = threadId,
    projectId = "project",
    title = threadId,
    createdAt = "2026-01-01T00:00:00Z",
    updatedAt = "2026-01-01T00:00:00Z",
    session = status?.let {
        ThreadSession(
            threadId = threadId,
            status = it,
            updatedAt = "2026-01-01T00:00:00Z",
        )
    },
)
