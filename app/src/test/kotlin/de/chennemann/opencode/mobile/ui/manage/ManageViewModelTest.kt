package de.chennemann.opencode.mobile.ui.manage

import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.domain.session.ServerState
import de.chennemann.opencode.mobile.domain.session.SessionServiceApi
import de.chennemann.opencode.mobile.domain.session.SessionState
import de.chennemann.opencode.mobile.domain.session.SessionUiState
import de.chennemann.opencode.mobile.navigation.LogsRoute
import de.chennemann.opencode.mobile.navigation.NavEvent
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
        val viewModel = ManageViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(
            projects = listOf(
                ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true),
                ProjectState(id = "p2", worktree = "/repo/other", name = "Other", favorite = false),
            ),
            selectedProject = "/repo/main",
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
        val viewModel = ManageViewModel(service, lanes(main, worker))
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
        val viewModel = ManageViewModel(service, lanes(main, worker))
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
        val viewModel = ManageViewModel(service, lanes(main, worker))

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
        val viewModel = ManageViewModel(service, lanes(main, worker))

        viewModel.onEvent(ManageEvent.Connect("http://127.0.0.1"))

        assertEquals(listOf("http://127.0.0.1"), service.urls)
        assertEquals(1, service.refreshCalls)
    }

    @Test
    fun opensLogsAndNavigatesToLogsScreen() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ManageViewModel(service, lanes(main, worker))

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
        val viewModel = ManageViewModel(service, lanes(main, worker))

        val nav = async { viewModel.nav.first() }
        advanceUntilIdle()
        viewModel.onEvent(ManageEvent.BackRequested)
        advanceUntilIdle()

        assertEquals(NavEvent.NavigateBack, nav.await())
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

    override fun toggleProjectFavorite(worktree: String) = Unit

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
