package de.chennemann.agentic.ui.manage

import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.session.ServerState
import de.chennemann.agentic.domain.session.SessionServiceApi
import de.chennemann.agentic.domain.session.SessionState
import de.chennemann.agentic.domain.session.SessionUiState
import de.chennemann.agentic.domain.projects.LocalProjectInfo
import de.chennemann.agentic.domain.projects.ProjectService
import de.chennemann.agentic.domain.servers.ServerConnectionState
import de.chennemann.agentic.domain.servers.ServerInfo
import de.chennemann.agentic.domain.servers.ServerService
import de.chennemann.agentic.navigation.LogsRoute
import de.chennemann.agentic.navigation.NavEvent
import kotlinx.coroutines.CoroutineScope
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
        val viewModel = ManageViewModel(serverService, projectService, StubSessionService(), lanes(main, worker))
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
        val sessionService = StubSessionService()
        val viewModel = ManageViewModel(serverService, projectService, sessionService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.Connect("http://127.0.0.1"))
        advanceUntilIdle()

        assertEquals(listOf("http://127.0.0.1"), serverService.connectRequests)
        assertEquals(listOf("http://127.0.0.1"), sessionService.updateUrlRequests)
        assertEquals(1, sessionService.refreshCalls)
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
        val sessionService = StubSessionService()
        val viewModel = ManageViewModel(serverService, projectService, sessionService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.ProjectsRefreshRequested)
        advanceUntilIdle()

        assertEquals(listOf("server-1|http://127.0.0.1"), projectService.syncRequests)
        assertEquals(listOf("http://127.0.0.1"), sessionService.updateUrlRequests)
        assertEquals(1, sessionService.refreshCalls)
    }

    @Test
    fun refreshProjectsSkipsSyncWhenNoServerConnected() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val serverService = StubServerService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(serverService, projectService, StubSessionService(), lanes(main, worker))

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
        val viewModel = ManageViewModel(serverService, projectService, StubSessionService(), lanes(main, worker))

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
        val viewModel = ManageViewModel(serverService, projectService, StubSessionService(), lanes(main, worker))

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
        val projectService = StubProjectService().also {
            it.projects.value = listOf(
                LocalProjectInfo(
                    id = "project-42",
                    serverId = "server-1",
                    name = "Main",
                    path = "/repo/main",
                    pinned = false,
                ),
            )
        }
        val sessionService = StubSessionService()
        val viewModel = ManageViewModel(serverService, projectService, sessionService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.ProjectFavoriteToggled("project-42"))
        advanceUntilIdle()

        assertEquals(listOf("project-42"), projectService.toggleRequests)
        assertEquals(listOf("/repo/main"), sessionService.toggleProjectFavoriteRequests)
    }

    @Test
    fun removesPersistentProjectUsingProjectServiceById() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val serverService = StubServerService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(serverService, projectService, StubSessionService(), lanes(main, worker))

        viewModel.onEvent(ManageEvent.ProjectRemoved("project-42"))
        advanceUntilIdle()

        assertEquals(listOf("project-42"), projectService.removeRequests)
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
    override val projects = MutableStateFlow<List<LocalProjectInfo>>(emptyList())
    val toggleRequests = mutableListOf<String>()
    val removeRequests = mutableListOf<String>()
    val syncRequests = mutableListOf<String>()

    override suspend fun togglePinnedById(projectId: String): Boolean {
        toggleRequests += projectId
        return true
    }

    override suspend fun removeById(projectId: String): Boolean {
        removeRequests += projectId
        return true
    }

    override suspend fun syncServerProjects(serverId: String, baseUrl: String): List<LocalProjectInfo> {
        syncRequests += "$serverId|$baseUrl"
        return emptyList()
    }
}

private class StubSessionService : SessionServiceApi {
    val updateUrlRequests = mutableListOf<String>()
    var refreshCalls: Int = 0
    override val state = MutableStateFlow(
        SessionUiState(
            url = "",
            discovered = null,
            status = ServerState.Idle,
            projects = emptyList(),
            selectedProject = null,
            commands = emptyList(),
            sessions = emptyList(),
            activeSessions = emptyList(),
            focusedSession = null,
            focusedMessages = emptyList(),
            canLoadMoreMessages = false,
            loadingMoreMessages = false,
            loadingProjects = false,
            loadingSessions = false,
            sessionRecentOnly = false,
            quickPinInclude = emptySet(),
            quickPinExclude = emptySet(),
            quickProcessing = emptySet(),
            quickUnread = emptySet(),
            message = null,
        ),
    )
    val toggleProjectFavoriteRequests = mutableListOf<String>()

    override fun start(scope: CoroutineScope) = Unit
    override fun updateUrl(value: String) {
        updateUrlRequests += value
    }
    override fun useDiscovered() = Unit
    override fun refresh() {
        refreshCalls += 1
    }
    override fun selectProject(worktree: String) = Unit
    override fun toggleProjectFavorite(worktree: String) {
        toggleProjectFavoriteRequests += worktree
    }
    override fun removeProject(worktree: String) = Unit
    override fun toggleSessionQuickPin(session: SessionState, systemPinned: Boolean) = Unit
    override suspend fun createSessionAndFocus(worktree: String): Boolean = false
    override fun openSession(session: SessionState) = Unit
    override fun send(text: String, agent: String) = Unit
    override fun loadMoreMessages() = Unit
    override fun archiveSession(session: SessionState) = Unit
    override fun renameSession(session: SessionState, title: String) = Unit
    override suspend fun cachedSessionsForProject(worktree: String, limit: Int?): List<SessionState> = emptyList()
    override suspend fun sessionsForProject(worktree: String, limit: Int?): List<SessionState> = emptyList()
}

private class StubServerService : ServerService {
    val connected = MutableStateFlow<ServerInfo>(ServerInfo.NONE)
    val connection = MutableStateFlow<ServerConnectionState>(ServerConnectionState.Idle)
    val connectRequests = mutableListOf<String>()

    override val connectedServer: Flow<ServerInfo> = connected
    override val connectionState: Flow<ServerConnectionState> = connection

    override suspend fun connect(url: String): Boolean {
        connectRequests += url
        return true
    }

    override suspend fun removeById(serverId: String): Boolean {
        return false
    }

    override suspend fun heartbeat() {
    }
}
