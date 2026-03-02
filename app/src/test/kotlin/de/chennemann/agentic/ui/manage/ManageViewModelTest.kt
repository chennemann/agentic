package de.chennemann.agentic.ui.manage

import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.session.ProjectState
import de.chennemann.agentic.domain.session.ServerState
import de.chennemann.agentic.domain.session.SessionServiceApi
import de.chennemann.agentic.domain.session.SessionState
import de.chennemann.agentic.domain.session.SessionUiState
import de.chennemann.agentic.domain.v2.projects.LocalProjectInfo
import de.chennemann.agentic.domain.v2.projects.ProjectService as ProjectServiceV2
import de.chennemann.agentic.navigation.LogsRoute
import de.chennemann.agentic.navigation.NavEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    fun mapsProjectsOnInjectedDispatcher() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(service, projectService, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(selectedProject = "/repo/main")
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
        assertEquals(1, service.startCalls)
        assertEquals(listOf("main", "other"), value.projects.map { it.name })
        assertEquals(listOf(true, false), value.projects.map { it.favorite })
        assertEquals("/repo/main", value.selectedProject)
        collect.cancel()
    }

    @Test
    fun removesSelectedProjectFromList() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(service, projectService, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(
            projects = listOf(
                ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true),
            ),
            selectedProject = "/repo/main",
        )
        advanceUntilIdle()

        viewModel.onEvent(ManageEvent.ProjectRemoved("/repo/main"))
        advanceUntilIdle()

        assertEquals(listOf("/repo/main"), service.removeRequests)
        collect.cancel()
    }

    @Test
    fun loadsProjectFromRequestedPath() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(service, projectService, lanes(main, worker))
        viewModel.onEvent(ManageEvent.LoadProjectRequested(" /repo/alpha "))
        advanceUntilIdle()

        assertEquals(listOf("/repo/alpha"), service.selectRequests)
    }

    @Test
    fun ignoresBlankLoadProjectRequest() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(service, projectService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.LoadProjectRequested("   "))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), service.selectRequests)
    }

    @Test
    fun triggersConnectAction() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(service, projectService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.Connect("http://127.0.0.1"))

        assertEquals(listOf("http://127.0.0.1"), service.urls)
        assertEquals(1, service.refreshCalls)
    }

    @Test
    fun refreshProjectsRequestsServiceRefresh() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(service, projectService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.ProjectsRefreshRequested)

        assertEquals(1, service.refreshCalls)
    }

    @Test
    fun opensLogsAndNavigatesToLogsScreen() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(service, projectService, lanes(main, worker))

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
        val service = StubSessionService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(service, projectService, lanes(main, worker))

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
        val service = StubSessionService()
        val projectService = StubProjectService()
        val viewModel = ManageViewModel(service, projectService, lanes(main, worker))

        viewModel.onEvent(ManageEvent.ProjectFavoriteToggled("project-42"))
        advanceUntilIdle()

        assertEquals(listOf("project-42"), projectService.toggleRequests)
        assertEquals(emptyList<String>(), service.toggleFavoriteRequests)
    }

    private fun state(
        projects: List<ProjectState> = emptyList(),
        selectedProject: String? = null,
    ): SessionUiState {
        return SessionUiState(
            url = "http://127.0.0.1",
            discovered = null,
            status = ServerState.Connected("1"),
            projects = projects,
            selectedProject = selectedProject,
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
        )
    }

    private fun lanes(main: TestDispatcher, worker: TestDispatcher): DispatcherProvider {
        return object : DispatcherProvider {
            override val io = worker
            override val default = worker
            override val mainImmediate = main
        }
    }
}

private class StubSessionService : SessionServiceApi {
    override val state = MutableStateFlow(
        SessionUiState(
            url = "http://127.0.0.1",
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
        )
    )
    var startCalls = 0
    var refreshCalls = 0
    val selectRequests = mutableListOf<String>()
    val toggleFavoriteRequests = mutableListOf<String>()
    val removeRequests = mutableListOf<String>()
    val urls = mutableListOf<String>()

    override fun start(scope: CoroutineScope) {
        startCalls += 1
    }

    override fun updateUrl(value: String) {
        urls += value
    }

    override fun useDiscovered() = Unit

    override fun refresh() {
        refreshCalls += 1
    }

    override fun selectProject(worktree: String) {
        selectRequests += worktree
    }

    override fun toggleProjectFavorite(worktree: String) {
        toggleFavoriteRequests += worktree
    }

    override fun removeProject(worktree: String) {
        removeRequests += worktree
    }

    override fun toggleSessionQuickPin(session: SessionState, systemPinned: Boolean) = Unit

    override suspend fun createSessionAndFocus(worktree: String): Boolean {
        return false
    }

    override fun openSession(session: SessionState) = Unit

    override fun send(text: String, agent: String) = Unit

    override fun loadMoreMessages() = Unit

    override fun archiveSession(session: SessionState) = Unit

    override fun renameSession(session: SessionState, title: String) = Unit

    override suspend fun cachedSessionsForProject(worktree: String, limit: Int?): List<SessionState> {
        return emptyList()
    }

    override suspend fun sessionsForProject(worktree: String, limit: Int?): List<SessionState> {
        return emptyList()
    }
}

private class StubProjectService : ProjectServiceV2 {
    val projects = MutableStateFlow<List<LocalProjectInfo>>(emptyList())
    val toggleRequests = mutableListOf<String>()

    override fun observeProjects(serverId: String?) = projects

    override suspend fun togglePinnedById(projectId: String): Boolean {
        toggleRequests += projectId
        return true
    }

    override suspend fun syncServerProjects(serverId: String, baseUrl: String): List<LocalProjectInfo> {
        return emptyList()
    }
}
