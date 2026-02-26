package de.chennemann.opencode.mobile.ui.manage

import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.domain.session.ServerState
import de.chennemann.opencode.mobile.domain.session.SessionServiceApi
import de.chennemann.opencode.mobile.domain.session.SessionState
import de.chennemann.opencode.mobile.domain.session.SessionUiState
import de.chennemann.opencode.mobile.navigation.AgentChatRoute
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManageViewModelTest {
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun mapsProjectsAndSectionsOnInjectedDispatcher() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ManageViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(
            projects = listOf(
                ProjectState(id = "p1", worktree = "/repo/main", name = "Main", sandboxes = listOf("/repo/main/s1"), favorite = true),
                ProjectState(id = "p2", worktree = "/repo/other", name = "Other", favorite = false),
            ),
            selectedProject = "/repo/main",
            sessions = listOf(
                SessionState(id = "s1", title = "One", version = "1", directory = "/repo/main", updatedAt = 100),
                SessionState(id = "s2", title = "Two", version = "1", directory = "/repo/main", updatedAt = 300),
                SessionState(id = "s3", title = "Three", version = "1", directory = "/repo/main/s1", updatedAt = 200),
            ),
        )

        advanceUntilIdle()

        val value = viewModel.state.value
        assertEquals(1, service.startCalls)
        assertEquals(listOf("main"), value.favoriteProjects.map { it.name })
        assertEquals(listOf("other"), value.otherProjects.map { it.name })
        assertEquals(listOf("/repo/main", "/repo/main/s1"), value.workspaceOptions.map { it.directory })
        assertEquals(2, value.sessionSections.size)
        assertEquals(listOf("s2", "s1"), value.sessionSections[0].sessions.map { it.id })
        collect.cancel()
    }

    @Test
    fun createsSessionForSelectedWorkspace() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ManageViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(
            projects = listOf(
                ProjectState(id = "p1", worktree = "/repo/main", name = "Main", sandboxes = listOf("/repo/main/s1"), favorite = true),
            ),
            selectedProject = "/repo/main",
        )
        advanceUntilIdle()

        val nav = async { viewModel.nav.first() }
        viewModel.onEvent(ManageEvent.WorkspaceSelected("/repo/main/s1"))
        viewModel.onEvent(ManageEvent.SessionRequested(null))
        advanceUntilIdle()

        assertEquals(listOf("/repo/main/s1"), service.createRequests)
        assertEquals(NavEvent.NavigateTo(AgentChatRoute), nav.await())
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
    fun filtersProjectsByQueryFromNameAndPath() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ManageViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(
            projects = listOf(
                ProjectState(id = "p1", worktree = "/repo/alpha", name = "Alpha", favorite = true),
                ProjectState(id = "p2", worktree = "/workspaces/beta", name = "Beta", favorite = false),
            ),
            selectedProject = "/repo/alpha",
        )
        advanceUntilIdle()

        viewModel.onEvent(ManageEvent.ProjectQueryChanged("  ALPha  "))
        advanceUntilIdle()
        assertEquals(listOf("alpha"), viewModel.state.value.favoriteProjects.map { it.name })
        assertEquals(emptyList<ProjectState>(), viewModel.state.value.otherProjects)

        viewModel.onEvent(ManageEvent.ProjectQueryChanged("WORKSPACES"))
        advanceUntilIdle()
        assertEquals(emptyList<ProjectState>(), viewModel.state.value.favoriteProjects)
        assertEquals(listOf("beta"), viewModel.state.value.otherProjects.map { it.name })
        collect.cancel()
    }

    @Test
    fun fallsBackToSelectedProjectWhenWorkspaceProjectMissing() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ManageViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/known", name = "Known", favorite = true)),
            selectedProject = "/repo/missing/",
            sessions = listOf(SessionState(id = "s1", title = "One", version = "1", directory = "/repo/missing", updatedAt = 10)),
        )
        advanceUntilIdle()

        val value = viewModel.state.value
        assertEquals(listOf("/repo/missing"), value.workspaceOptions.map { it.directory })
        assertEquals("/repo/missing", value.selectedWorkspace)
        assertEquals("Local: missing", value.selectedWorkspaceName)
        assertEquals(listOf("s1"), value.sessionSections.first().sessions.map { it.id })
        collect.cancel()
    }

    @Test
    fun fallsBackToFirstWorkspaceWhenSelectionDoesNotExist() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ManageViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(
            projects = listOf(
                ProjectState(id = "p1", worktree = "/repo/main", name = "Main", sandboxes = listOf("/repo/main/s1"), favorite = true),
            ),
            selectedProject = "/repo/main",
        )
        advanceUntilIdle()

        val nav = async { viewModel.nav.first() }
        viewModel.onEvent(ManageEvent.WorkspaceSelected("/repo/unknown"))
        viewModel.onEvent(ManageEvent.SessionRequested(null))
        advanceUntilIdle()

        assertEquals("/repo/main", viewModel.state.value.selectedWorkspace)
        assertEquals(listOf("/repo/main"), service.createRequests)
        assertEquals(NavEvent.NavigateTo(AgentChatRoute), nav.await())
        collect.cancel()
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
    fun opensSessionAndNavigatesToConversation() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ManageViewModel(service, lanes(main, worker))
        val session = SessionState(id = "s1", title = "One", version = "1", directory = "/repo/main", updatedAt = 100)
        service.state.value = state(sessions = listOf(session))

        val nav = async { viewModel.nav.first() }
        advanceUntilIdle()
        viewModel.onEvent(ManageEvent.SessionRequested(session.id))
        advanceUntilIdle()

        assertEquals(listOf("s1"), service.openRequests.map { it.id })
        assertEquals(NavEvent.NavigateTo(AgentChatRoute), nav.await())
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

    private fun state(
        projects: List<ProjectState> = emptyList(),
        selectedProject: String? = null,
        sessions: List<SessionState> = emptyList(),
    ): SessionUiState {
        return SessionUiState(
            url = "http://127.0.0.1",
            discovered = null,
            status = ServerState.Connected("1"),
            projects = projects,
            selectedProject = selectedProject,
            commands = emptyList(),
            sessions = sessions,
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
    val createRequests = mutableListOf<String>()
    val removeRequests = mutableListOf<String>()
    val openRequests = mutableListOf<SessionState>()
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

    override fun selectProject(worktree: String) = Unit

    override fun toggleProjectFavorite(worktree: String) = Unit

    override fun removeProject(worktree: String) {
        removeRequests += worktree
    }

    override fun toggleSessionQuickPin(session: SessionState, systemPinned: Boolean) = Unit

    override suspend fun createSessionAndFocus(worktree: String): Boolean {
        createRequests += worktree
        return true
    }

    override fun openSession(session: SessionState) {
        openRequests += session
    }

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
