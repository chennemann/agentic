package de.chennemann.agentic.ui.manage

import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.session.ServerState
import de.chennemann.agentic.domain.v2.projects.LocalProjectInfo
import de.chennemann.agentic.domain.v2.projects.ProjectService
import de.chennemann.agentic.domain.v2.servers.ServerInfo
import de.chennemann.agentic.domain.v2.servers.ServerService
import de.chennemann.agentic.navigation.LogsRoute
import de.chennemann.agentic.navigation.NavEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManageViewModelTest {
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun mapsConnectedServerAndProjectsOnInjectedDispatcher() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val serverService = StubServerService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(serverService, projectService, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }

        serverService.connected.value = ServerInfo.ConnectedServerInfo(
            id = "server-1",
            url = "http://127.0.0.1",
            lastConnectedAt = 1L,
        )
        projectService.projects.value = listOf(
            LocalProjectInfo(
                id = "p1",
                serverId = "server-1",
                name = "Main",
                path = "/repo/main",
                pinned = true,
            ),
            LocalProjectInfo(
                id = "p2",
                serverId = "server-1",
                name = "Other",
                path = "/repo/other",
                pinned = false,
            ),
        )

        advanceUntilIdle()

        val value = viewModel.state.value
        assertEquals("http://127.0.0.1", value.url)
        assertEquals(ServerState.Connected("http://127.0.0.1"), value.status)
        assertEquals(false, value.loadingProjects)
        assertEquals(listOf("main", "other"), value.projects.map { it.name })
        assertEquals(listOf(true, false), value.projects.map { it.favorite })

        collect.cancel()
    }

    @Test
    fun triggersConnectAction() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val serverService = StubServerService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(serverService, projectService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.Connect("http://127.0.0.1"))
        advanceUntilIdle()

        assertEquals(listOf("http://127.0.0.1"), serverService.connectRequests)
    }

    @Test
    fun refreshProjectsRequestsSyncForConnectedServer() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val serverService = StubServerService().also {
            it.connected.value = ServerInfo.ConnectedServerInfo(
                id = "server-1",
                url = "http://127.0.0.1",
                lastConnectedAt = 1L,
            )
        }
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(serverService, projectService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.ProjectsRefreshRequested)
        advanceUntilIdle()

        assertEquals(listOf("server-1|http://127.0.0.1"), projectService.syncRequests)
    }

    @Test
    fun refreshProjectsSkipsSyncWhenNoServerConnected() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val serverService = StubServerService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(serverService, projectService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.ProjectsRefreshRequested)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), projectService.syncRequests)
    }

    @Test
    fun opensLogsAndNavigatesToLogsScreen() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val serverService = StubServerService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(serverService, projectService, lanes(main, worker))

        val nav = async { viewModel.nav.first() }
        advanceUntilIdle()
        viewModel.onEvent(ManageEvent.LogsRequested)
        advanceUntilIdle()

        assertEquals(NavEvent.NavigateTo(LogsRoute), nav.await())
    }

    @Test
    fun backRequestedEmitsNavigateBack() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val serverService = StubServerService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(serverService, projectService, lanes(main, worker))

        val nav = async { viewModel.nav.first() }
        advanceUntilIdle()
        viewModel.onEvent(ManageEvent.BackRequested)
        advanceUntilIdle()

        assertEquals(NavEvent.NavigateBack, nav.await())
    }

    @Test
    fun togglesFavoriteUsingProjectServiceById() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val serverService = StubServerService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(serverService, projectService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.ProjectFavoriteToggled("project-42"))
        advanceUntilIdle()

        assertEquals(listOf("project-42"), projectService.toggleRequests)
    }

    private fun lanes(main: TestDispatcher, worker: TestDispatcher): DispatcherProvider {
        return object : DispatcherProvider {
            override val io = worker
            override val default = worker
            override val mainImmediate = main
        }
    }
}

private class StubProjectService : ProjectService {
    val projects = MutableStateFlow<List<LocalProjectInfo>>(emptyList())
    val toggleRequests = mutableListOf<String>()
    val syncRequests = mutableListOf<String>()

    override fun observeProjects(serverId: String?): Flow<List<LocalProjectInfo>> {
        return projects
    }

    override suspend fun togglePinnedById(projectId: String): Boolean {
        toggleRequests += projectId
        return true
    }

    override suspend fun syncServerProjects(serverId: String, baseUrl: String): List<LocalProjectInfo> {
        syncRequests += "$serverId|$baseUrl"
        return emptyList()
    }
}

private class StubServerService : ServerService {
    val connected = MutableStateFlow<ServerInfo>(ServerInfo.NONE)
    val connectRequests = mutableListOf<String>()

    override val connectedServer: Flow<ServerInfo> = connected

    override suspend fun connect(url: String): Boolean {
        connectRequests += url
        return true
    }

    override suspend fun heartbeat() {
    }
}
