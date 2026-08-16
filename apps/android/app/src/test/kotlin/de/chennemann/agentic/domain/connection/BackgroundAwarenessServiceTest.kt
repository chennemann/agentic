package de.chennemann.agentic.domain.connection

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.ClientActivityReport
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.EnvironmentPlatform
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentCapabilities
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ServerAuthDescriptor
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.ThreadSession
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundAwarenessServiceTest {
    @Test
    fun `active work renews in background and withdrawing demand stops renewals`() = runTest {
        val environments = AwarenessEnvironmentRepository()
        val orchestration = AwarenessOrchestrationRepository()
        val rpc = RecordingActivityRpc(failFirst = true)
        val service = BackgroundAwarenessService(
            environments = environments,
            orchestration = orchestration,
            credentials = FixedCredentialStore,
            rpc = rpc,
            installationId = ClientInstallationId { "installation-1" },
            scope = backgroundScope,
            reportIntervalMillis = 25,
            now = { Instant.parse("2026-08-16T12:00:00Z") },
        )

        runCurrent()
        assertEquals(setOf("thread-1"), rpc.reports.single().threadIds)
        assertEquals("background", rpc.reports.single().appState)

        advanceTimeBy(25)
        runCurrent()
        assertEquals(2, rpc.reports.size)

        orchestration.shell.value = ProjectionState(shellSnapshot(emptyList()))
        runCurrent()
        assertEquals(emptySet<String>(), rpc.reports.last().threadIds)
        val releasedAt = rpc.reports.size

        advanceTimeBy(100)
        runCurrent()
        assertEquals(releasedAt, rpc.reports.size)

        service.setVisible(true)
        runCurrent()
        assertEquals(true, rpc.reports.last().visible)
    }
}

private class RecordingActivityRpc(private val failFirst: Boolean) : T3RpcClient {
    val reports = mutableListOf<ClientActivityReport>()

    override suspend fun reportClientActivity(baseUrl: String, bearerToken: String, report: ClientActivityReport) {
        reports += report
        if (failFirst && reports.size == 1) error("transient failure")
    }

    override suspend fun serverConfig(baseUrl: String, bearerToken: String) = error("unused")
    override suspend fun dispatch(baseUrl: String, bearerToken: String, command: ClientOrchestrationCommand) = DispatchResult(0)
    override fun shellStream(baseUrl: String, bearerToken: String, afterSequence: Long?, requestCompletionMarker: Boolean): Flow<OrchestrationShellStreamItem> = error("unused")
    override fun threadStream(baseUrl: String, bearerToken: String, threadId: String, afterSequence: Long?, requestCompletionMarker: Boolean): Flow<OrchestrationThreadStreamItem> = error("unused")
}

private object FixedCredentialStore : CredentialStore {
    override suspend fun read(environmentId: String) = "token"
    override suspend fun write(environmentId: String, bearerToken: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
}

private class AwarenessEnvironmentRepository : EnvironmentRepository {
    private val environment = SavedEnvironment("environment-1", "Local", "https://local", "linux", "x64", "1", true, null)
    override val environments = MutableStateFlow(listOf(environment))
    override val activeEnvironment = MutableStateFlow<SavedEnvironment?>(environment)
    override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) = Unit
    override suspend fun select(environmentId: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
    override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
}

private class AwarenessOrchestrationRepository : OrchestrationRepository {
    override val clientConfig = MutableStateFlow(ProjectionState(awarenessConfig()))
    override val shell = MutableStateFlow(ProjectionState(shellSnapshot(listOf(activeThread()))))
    override val focusedThread = MutableStateFlow<ProjectionState<OrchestrationThreadDetailSnapshot>>(ProjectionState())
    override val focusedThreadId = MutableStateFlow<String?>(null)
    override val selectedProjectId = MutableStateFlow<String?>(null)
    override suspend fun loadCached(environmentId: String) = Unit
    override suspend fun setClientConfig(environmentId: String, config: ServerConfig, source: ProjectionSource) = Unit
    override suspend fun setShellSnapshot(environmentId: String, snapshot: OrchestrationShellSnapshot, source: ProjectionSource) = Unit
    override suspend fun applyShellItem(environmentId: String, item: OrchestrationShellStreamItem): Reduction<ProjectionState<OrchestrationShellSnapshot>> = error("unused")
    override suspend fun focusThread(environmentId: String, threadId: String?) = Unit
    override suspend fun selectProject(environmentId: String, projectId: String?) = Unit
    override suspend fun setThreadSnapshot(environmentId: String, snapshot: OrchestrationThreadDetailSnapshot, source: ProjectionSource) = Unit
    override suspend fun applyThreadItem(environmentId: String, threadId: String, item: OrchestrationThreadStreamItem): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>> = error("unused")
    override suspend fun clearEnvironment(environmentId: String) = Unit
}

private fun awarenessConfig() = ServerConfig(
    environment = ExecutionEnvironmentDescriptor(
        "environment-1",
        "Local",
        EnvironmentPlatform("linux", "x64"),
        "1",
        ExecutionEnvironmentCapabilities(backgroundActivity = true),
    ),
    auth = ServerAuthDescriptor("remote", emptyList(), emptyList(), "session"),
    providers = emptyList(),
)

private fun shellSnapshot(threads: List<OrchestrationThreadShell>) =
    OrchestrationShellSnapshot(emptyList(), threads, 1, "2026-08-16T12:00:00Z")

private fun activeThread() = OrchestrationThreadShell(
    id = "thread-1",
    projectId = "project-1",
    title = "Active",
    createdAt = "2026-08-16T12:00:00Z",
    updatedAt = "2026-08-16T12:00:00Z",
    session = ThreadSession("thread-1", "running", updatedAt = "2026-08-16T12:00:00Z"),
)
