package de.chennemann.agentic.domain.connection

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.EnvironmentConfigClient
import de.chennemann.agentic.data.t3.EnvironmentMetadataClient
import de.chennemann.agentic.data.t3.OrchestrationSnapshotClient
import de.chennemann.agentic.data.t3.OrchestrationStreamClient
import de.chennemann.agentic.data.t3.T3TransportException
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.t3.contract.EnvironmentClientConfig
import de.chennemann.agentic.t3.contract.EnvironmentPlatform
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentCapabilities
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetail
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ServerAuthDescriptor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSupervisorTest {
    @Test
    fun `foreground wake cancels a stuck connection and starts fresh`() = runTest {
        val environment = savedEnvironment("one")
        val transport = SupervisorTransport(blockFirstDescriptor = true)
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(environment),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            config = transport,
            snapshots = transport,
            streams = transport,
            network = OnlineMonitor,
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
    fun `network restoration cancels old streams and reconnects immediately`() = runTest {
        val environment = savedEnvironment("one")
        val network = MutableOnlineMonitor(true)
        val transport = SupervisorTransport()
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(environment),
            orchestration = SupervisorOrchestrationRepository(),
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            config = transport,
            snapshots = transport,
            streams = transport,
            network = network,
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
            config = transport,
            snapshots = transport,
            streams = transport,
            network = OnlineMonitor,
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
            config = transport,
            snapshots = transport,
            streams = transport,
            network = OnlineMonitor,
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
    fun `completed focused thread stream resumes without dropping shell connection`() = runTest {
        val transport = SupervisorTransport(completeFirstThreadStream = true)
        val orchestration = SupervisorOrchestrationRepository()
        val supervisor = ConnectionSupervisor(
            environments = SupervisorEnvironmentRepository(savedEnvironment("one")),
            orchestration = orchestration,
            credentials = SupervisorCredentialStore("token"),
            metadata = transport,
            config = transport,
            snapshots = transport,
            streams = transport,
            network = OnlineMonitor,
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
            config = transport,
            snapshots = transport,
            streams = transport,
            network = OnlineMonitor,
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
            config = transport,
            snapshots = transport,
            streams = transport,
            network = OnlineMonitor,
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
            config = transport,
            snapshots = transport,
            streams = transport,
            network = OnlineMonitor,
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
    private val completeFirstShellStream: Boolean = false,
    private val completeFirstThreadStream: Boolean = false,
    private val failFirstThreadStream: Boolean = false,
) : EnvironmentMetadataClient,
    EnvironmentConfigClient,
    OrchestrationSnapshotClient,
    OrchestrationStreamClient {
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
        return descriptor(baseUrl.substringAfter("https://").substringBefore('.'))
    }

    override suspend fun clientConfig(
        baseUrl: String,
        bearerToken: String,
    ): EnvironmentClientConfig = EnvironmentClientConfig(
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
        protocolVersion = 1,
    )

    override suspend fun shellSnapshot(
        baseUrl: String,
        bearerToken: String,
    ): OrchestrationShellSnapshot {
        shellBaseUrls += baseUrl
        return OrchestrationShellSnapshot(emptyList(), emptyList(), 0, "2026-01-01T00:00:00Z")
    }

    override suspend fun threadSnapshot(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
    ): OrchestrationThreadDetailSnapshot {
        threadRequests += threadId
        return threadSnapshot(threadId)
    }

    override fun shellStream(
        baseUrl: String,
        bearerToken: String,
        afterSequence: Long,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationShellStreamItem> {
        shellStreamRequests++
        return if (completeFirstShellStream && shellStreamRequests == 1) {
            flowOf(OrchestrationShellStreamItem.Synchronized)
        } else {
            cancellableFlow(OrchestrationShellStreamItem.Synchronized)
        }
    }

    override fun threadStream(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        afterSequence: Long,
        requestCompletionMarker: Boolean,
    ): Flow<OrchestrationThreadStreamItem> {
        threadStreamRequests++
        return when {
            completeFirstThreadStream && threadStreamRequests == 1 ->
                flowOf(OrchestrationThreadStreamItem.Synchronized)

            failFirstThreadStream && threadStreamRequests == 1 -> flow {
                emit(OrchestrationThreadStreamItem.Synchronized)
                throw T3TransportException.Network()
            }

            else -> cancellableFlow(OrchestrationThreadStreamItem.Synchronized)
        }
    }

    private fun <T> cancellableFlow(item: T): Flow<T> = flow {
        try {
            emit(item)
            awaitCancellation()
        } finally {
            cancelledStreams++
        }
    }
}

private class SupervisorOrchestrationRepository : OrchestrationRepository {
    override val clientConfig = MutableStateFlow(ProjectionState<EnvironmentClientConfig>())
    override val shell = MutableStateFlow(ProjectionState<OrchestrationShellSnapshot>())
    override val focusedThread = MutableStateFlow(ProjectionState<OrchestrationThreadDetailSnapshot>())
    override val focusedThreadId = MutableStateFlow<String?>(null)
    override val selectedProjectId = MutableStateFlow<String?>(null)

    override suspend fun loadCached(environmentId: String) = Unit

    override suspend fun setClientConfig(
        environmentId: String,
        config: EnvironmentClientConfig,
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
        shell.value = shell.value.copy(synchronized = true)
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
        item: OrchestrationThreadStreamItem,
    ): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>> {
        focusedThread.value = focusedThread.value.copy(synchronized = true)
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
    capabilities = ExecutionEnvironmentCapabilities(portableClientProtocol = 1),
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
