package de.chennemann.opencode.mobile.ui.chat

import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.CommandState
import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.domain.session.ServerState
import de.chennemann.opencode.mobile.domain.session.SessionServiceApi
import de.chennemann.opencode.mobile.domain.session.SessionState
import de.chennemann.opencode.mobile.domain.session.SessionUiState
import de.chennemann.opencode.mobile.navigation.NavEvent
import de.chennemann.opencode.mobile.navigation.SessionSelectionBottomSheetRoute
import de.chennemann.opencode.mobile.navigation.WorkspaceHubRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun mapsServiceStateOnInjectedDispatcher() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        val menuCollect = backgroundScope.launch(worker) { viewModel.quickSwitchMenu.collect {} }
        val focused = SessionState(
            id = "s1",
            title = "Session 1",
            version = "1",
            directory = "/repo/main",
            updatedAt = 100,
        )
        service.state.value = state(
            status = ServerState.Connected("1"),
            commands = listOf(CommandState(name = "help", description = "Help")),
            focusedSession = focused,
            focusedMessages = listOf(
                de.chennemann.opencode.mobile.domain.session.MessageState(id = "u1", role = "user", text = "Hi", sort = "1"),
                de.chennemann.opencode.mobile.domain.session.MessageState(id = "a1", role = "assistant", text = "Hello", sort = "2"),
            ),
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true)),
            activeSessions = listOf(focused),
        )

        advanceUntilIdle()

        val value = viewModel.state.value
        assertEquals(1, service.startCalls)
        assertEquals("Session 1", value.title)
        assertEquals(1, value.turns.size)
        assertEquals(listOf("new", "help"), value.slashSuggestions.map { it.name })
        assertEquals(1, value.quickSwitches.size)

        viewModel.onEvent(ConversationEvent.DraftChanged("/he"))
        advanceUntilIdle()

        assertEquals(listOf("help"), viewModel.state.value.slashSuggestions.map { it.name })
        menuCollect.cancel()
        collect.cancel()
    }

    @Test
    fun workspaceHubRequestedEmitsTypedNavigationAction() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))

        val nav = async { viewModel.nav.first() }
        advanceUntilIdle()
        viewModel.onEvent(ConversationEvent.WorkspaceHubRequested)
        advanceUntilIdle()

        assertEquals(NavEvent.NavigateTo(WorkspaceHubRoute), nav.await())
    }

    @Test
    fun sessionRequestedOpensKnownSessionById() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val session = SessionState(id = "s1", title = "One", version = "1", directory = "/repo/main", updatedAt = 100)
        service.state.value = state(activeSessions = listOf(session))

        viewModel.onEvent(ConversationEvent.SessionRequested("s1"))
        advanceUntilIdle()

        assertEquals(listOf("s1"), service.openRequests)
    }

    @Test
    fun sessionRequestedWithNullCreatesInProvidedWorktree() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))

        viewModel.onEvent(ConversationEvent.SessionRequested(null, "/repo/main"))
        advanceUntilIdle()

        assertEquals(listOf("/repo/main"), service.createRequests)
    }

    @Test
    fun quickSwitchLongPressLoadsMenuSessions() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        val menuCollect = backgroundScope.launch(worker) { viewModel.quickSwitchMenu.collect {} }
        val focused = SessionState(
            id = "s2",
            title = "Focused",
            version = "1",
            directory = "/repo/main",
            updatedAt = 200,
        )
        service.projectSessions["/repo/main"] = listOf(
            SessionState(id = "s1", title = "Old", version = "1", directory = "/repo/main", updatedAt = 100),
            SessionState(id = "s2", title = "Focused", version = "1", directory = "/repo/main", updatedAt = 200),
        )
        service.state.value = state(
            focusedSession = focused,
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true)),
            activeSessions = listOf(focused),
        )

        advanceUntilIdle()
        viewModel.onEvent(ConversationEvent.SessionsRequested("/repo/main"))
        advanceUntilIdle()

        val menu = viewModel.quickSwitchMenu.value
        assertEquals(listOf("/repo/main"), service.sessionRequests)
        assertEquals(listOf(11), service.sessionRequestLimits)
        assertEquals(listOf(11), service.cachedRequestLimits)
        assertTrue(menu != null)
        assertFalse(menu!!.loading)
        assertEquals(listOf("s2", "s1"), menu.sessions.map { it.id })
        menuCollect.cancel()
        collect.cancel()
    }

    @Test
    fun sessionsRequestedEmitsQuickSwitchSheetNavigation() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))

        val nav = async { viewModel.nav.first() }
        advanceUntilIdle()
        viewModel.onEvent(ConversationEvent.SessionsRequested("/repo/main"))
        advanceUntilIdle()

        assertEquals(NavEvent.NavigateTo(SessionSelectionBottomSheetRoute("/repo/main")), nav.await())
    }

    @Test
    fun quickSwitchMenuLoadsMoreSessionsInPages() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        val menuCollect = backgroundScope.launch(worker) { viewModel.quickSwitchMenu.collect {} }
        val focused = SessionState(
            id = "s12",
            title = "Focused",
            version = "1",
            directory = "/repo/main",
            updatedAt = 1_200,
        )
        service.projectSessions["/repo/main"] = (1..12)
            .map {
                SessionState(
                    id = "s$it",
                    title = "Session $it",
                    version = "1",
                    directory = "/repo/main",
                    updatedAt = it * 100L,
                )
            }
            .sortedByDescending { it.updatedAt }
        service.state.value = state(
            focusedSession = focused,
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true)),
            activeSessions = listOf(focused),
        )

        advanceUntilIdle()
        viewModel.onEvent(ConversationEvent.SessionsRequested("/repo/main"))
        advanceUntilIdle()

        val initial = viewModel.quickSwitchMenu.value
        assertTrue(initial != null)
        assertEquals(11, initial!!.sessions.size)
        assertTrue(initial.canLoadMore)
        assertEquals(listOf(11), service.sessionRequestLimits)
        assertEquals(listOf(11), service.cachedRequestLimits)

        viewModel.onEvent(ConversationEvent.MoreSessionsRequested)
        advanceUntilIdle()

        val next = viewModel.quickSwitchMenu.value
        assertTrue(next != null)
        assertEquals(12, next!!.sessions.size)
        assertFalse(next.canLoadMore)
        assertEquals(listOf(11, 22), service.sessionRequestLimits)
        menuCollect.cancel()
        collect.cancel()
    }

    @Test
    fun quickSwitchArchiveRemovesSessionAndDelegates() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        val menuCollect = backgroundScope.launch(worker) { viewModel.quickSwitchMenu.collect {} }
        val focused = SessionState(
            id = "s2",
            title = "Focused",
            version = "1",
            directory = "/repo/main",
            updatedAt = 200,
        )
        service.projectSessions["/repo/main"] = listOf(
            SessionState(id = "s1", title = "Old", version = "1", directory = "/repo/main", updatedAt = 100),
            focused,
        )
        service.state.value = state(
            focusedSession = focused,
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true)),
            activeSessions = listOf(focused),
        )

        advanceUntilIdle()
        viewModel.onEvent(ConversationEvent.SessionsRequested("/repo/main"))
        advanceUntilIdle()

        viewModel.onEvent(ConversationEvent.SessionArchiveRequested(focused))
        advanceUntilIdle()

        val menu = viewModel.quickSwitchMenu.value
        assertTrue(menu != null)
        assertEquals(listOf("s1"), menu!!.sessions.map { it.id })
        assertEquals(listOf("s2"), service.archiveRequests)
        menuCollect.cancel()
        collect.cancel()
    }

    @Test
    fun quickSwitchOrdersByProjectNameInitial() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(
            projects = listOf(
                ProjectState(id = "p1", worktree = "/repo/zeta", name = "Zeta", favorite = true),
                ProjectState(id = "p2", worktree = "/repo/alpha", name = "Alpha", favorite = true),
            ),
            activeSessions = listOf(
                SessionState(id = "s-z", title = "Z", version = "1", directory = "/repo/zeta", updatedAt = 400),
                SessionState(id = "s-a", title = "A", version = "1", directory = "/repo/alpha", updatedAt = 100),
            ),
        )

        advanceUntilIdle()

        assertEquals(listOf("Alpha", "Zeta"), viewModel.state.value.quickSwitches.map { it.project })
        collect.cancel()
    }

    @Test
    fun quickSwitchCycleKeepsStableOrderAcrossStateRefresh() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        val sessions = listOf(
            SessionState(id = "s3", title = "Session 3", version = "1", directory = "/repo/main", updatedAt = 300),
            SessionState(id = "s2", title = "Session 2", version = "1", directory = "/repo/main", updatedAt = 200),
            SessionState(id = "s1", title = "Session 1", version = "1", directory = "/repo/main", updatedAt = 100),
        )
        service.state.value = state(
            focusedSession = sessions[0],
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true)),
            activeSessions = sessions,
        )

        advanceUntilIdle()
        assertEquals(
            listOf("s3", "s2", "s1"),
            viewModel.state.value.quickSwitches.single().cycleSessionIds,
        )

        val refreshed = listOf(
            SessionState(id = "s1", title = "Session 1", version = "1", directory = "/repo/main", updatedAt = 900),
            SessionState(id = "s3", title = "Session 3", version = "1", directory = "/repo/main", updatedAt = 800),
            SessionState(id = "s2", title = "Session 2", version = "1", directory = "/repo/main", updatedAt = 700),
        )
        service.state.value = state(
            focusedSession = refreshed[2],
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true)),
            activeSessions = refreshed,
        )

        advanceUntilIdle()
        assertEquals(
            listOf("s3", "s2", "s1"),
            viewModel.state.value.quickSwitches.single().cycleSessionIds,
        )
        collect.cancel()
    }

    @Test
    fun quickSwitchLongPressFailureKeepsCachedSessions() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        val menuCollect = backgroundScope.launch(worker) { viewModel.quickSwitchMenu.collect {} }
        val focused = SessionState(
            id = "s2",
            title = "Focused",
            version = "1",
            directory = "/repo/main",
            updatedAt = 200,
        )
        service.projectSessions["/repo/main"] = listOf(
            SessionState(id = "s2", title = "Focused", version = "1", directory = "/repo/main", updatedAt = 200),
            SessionState(id = "s1", title = "Old", version = "1", directory = "/repo/main", updatedAt = 100),
        )
        service.failSessionRequests = true
        service.state.value = state(
            focusedSession = focused,
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true)),
            activeSessions = listOf(focused),
        )

        advanceUntilIdle()
        viewModel.onEvent(ConversationEvent.SessionsRequested("/repo/main"))
        advanceUntilIdle()

        val menu = viewModel.quickSwitchMenu.value
        assertTrue(menu != null)
        assertFalse(menu!!.loading)
        assertEquals(listOf("s2", "s1"), menu.sessions.map { it.id })
        assertEquals(listOf(11), service.cachedRequestLimits)
        assertEquals(listOf(11), service.sessionRequestLimits)
        menuCollect.cancel()
        collect.cancel()
    }

    @Test
    fun quickSwitchAndMenuHideSubagentSessions() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        val menuCollect = backgroundScope.launch(worker) { viewModel.quickSwitchMenu.collect {} }
        val root = SessionState(
            id = "s-root",
            title = "Root",
            version = "1",
            directory = "/repo/main",
            updatedAt = 200,
        )
        val subagent = SessionState(
            id = "s-sub",
            title = "Subagent",
            version = "1",
            directory = "/repo/main",
            parentId = "s-root",
            updatedAt = 300,
        )
        service.projectSessions["/repo/main"] = listOf(root, subagent)
        service.state.value = state(
            focusedSession = root,
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true)),
            activeSessions = listOf(root, subagent),
        )

        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.quickSwitches.size)

        viewModel.onEvent(ConversationEvent.SessionsRequested("/repo/main"))
        advanceUntilIdle()

        assertEquals(listOf("s-root"), viewModel.quickSwitchMenu.value?.sessions?.map { it.id })
        menuCollect.cancel()
        collect.cancel()
    }

    @Test
    fun toolCallSessionTappedOpensKnownOrFallbackSession() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        val focused = SessionState(
            id = "s-root",
            title = "Root",
            version = "1",
            directory = "/repo/main",
            updatedAt = 100,
        )
        val knownSubagent = SessionState(
            id = "s-sub-known",
            title = "Known",
            version = "1",
            directory = "/repo/main",
            parentId = "s-root",
            updatedAt = 90,
        )
        service.state.value = state(
            focusedSession = focused,
            projects = listOf(ProjectState(id = "p1", worktree = "/repo/main", name = "Main", favorite = true)),
            activeSessions = listOf(focused, knownSubagent),
        )

        advanceUntilIdle()
        viewModel.onEvent(ConversationEvent.SubsessionRequested("s-sub-known"))
        viewModel.onEvent(ConversationEvent.SubsessionRequested("s-sub-fallback"))
        advanceUntilIdle()

        assertEquals(listOf("s-sub-known", "s-sub-fallback"), service.openRequests)
        assertEquals("s-root", service.openedSessions[0].parentId)
        assertEquals("s-root", service.openedSessions[1].parentId)
        assertEquals("/repo/main", service.openedSessions[1].directory)
        collect.cancel()
    }

    @Test
    fun sendTappedClearsDraftOnlyForNonBlankValues() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }

        advanceUntilIdle()
        viewModel.onEvent(ConversationEvent.DraftChanged("hello"))
        viewModel.onEvent(ConversationEvent.MessageSubmitted)
        advanceUntilIdle()

        assertEquals(listOf("hello"), service.sentTexts)
        assertEquals("", viewModel.state.value.draft)
        assertEquals(1L, viewModel.state.value.scroll)

        viewModel.onEvent(ConversationEvent.DraftChanged("   "))
        viewModel.onEvent(ConversationEvent.MessageSubmitted)
        advanceUntilIdle()

        assertEquals(listOf("hello", "   "), service.sentTexts)
        assertEquals("   ", viewModel.state.value.draft)
        assertEquals(1L, viewModel.state.value.scroll)
        collect.cancel()
    }

    @Test
    fun slashSuggestionsHandleEdgeCases() = runTest(TestCoroutineScheduler()) {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val service = StubSessionService()
        val viewModel = ConversationViewModel(service, lanes(main, worker))
        val collect = backgroundScope.launch(worker) { viewModel.state.collect {} }
        service.state.value = state(
            commands = listOf(
                CommandState(name = "help", description = "General help"),
                CommandState(name = "deploy", description = "Ship release"),
            ),
        )

        advanceUntilIdle()
        viewModel.onEvent(ConversationEvent.DraftChanged("/"))
        advanceUntilIdle()
        assertEquals(listOf("new", "help", "deploy"), viewModel.state.value.slashSuggestions.map { it.name })

        viewModel.onEvent(ConversationEvent.DraftChanged("/GEN"))
        advanceUntilIdle()
        assertEquals(listOf("help"), viewModel.state.value.slashSuggestions.map { it.name })

        viewModel.onEvent(ConversationEvent.DraftChanged("/help now"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.slashSuggestions.isEmpty())

        viewModel.onEvent(ConversationEvent.DraftChanged(" /help"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.slashSuggestions.isEmpty())
        collect.cancel()
    }

    private fun state(
        status: ServerState = ServerState.Idle,
        commands: List<CommandState> = emptyList(),
        focusedSession: SessionState? = null,
        focusedMessages: List<de.chennemann.opencode.mobile.domain.session.MessageState> = emptyList(),
        projects: List<ProjectState> = emptyList(),
        activeSessions: List<SessionState> = emptyList(),
    ): SessionUiState {
        return SessionUiState(
            url = "http://127.0.0.1",
            discovered = null,
            status = status,
            projects = projects,
            selectedProject = projects.firstOrNull()?.worktree,
            commands = commands,
            sessions = emptyList(),
            activeSessions = activeSessions,
            focusedSession = focusedSession,
            focusedMessages = focusedMessages,
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
    val sessionRequests = mutableListOf<String>()
    val sessionRequestLimits = mutableListOf<Int>()
    val cachedRequestLimits = mutableListOf<Int>()
    val archiveRequests = mutableListOf<String>()
    val createRequests = mutableListOf<String>()
    val openRequests = mutableListOf<String>()
    val openedSessions = mutableListOf<SessionState>()
    val sentTexts = mutableListOf<String>()
    val sentAgents = mutableListOf<String>()
    val projectSessions = linkedMapOf<String, List<SessionState>>()
    var failSessionRequests = false

    override fun start(scope: CoroutineScope) {
        startCalls += 1
    }

    override fun updateUrl(value: String) = Unit

    override fun useDiscovered() = Unit

    override fun refresh() = Unit

    override fun selectProject(worktree: String) = Unit

    override fun toggleProjectFavorite(worktree: String) = Unit

    override fun removeProject(worktree: String) = Unit

    override fun toggleSessionQuickPin(session: SessionState, systemPinned: Boolean) = Unit

    override suspend fun createSessionAndFocus(worktree: String): Boolean {
        createRequests += worktree
        return true
    }

    override fun openSession(session: SessionState) {
        openRequests += session.id
        openedSessions += session
    }

    override fun send(text: String, agent: String) {
        sentTexts += text
        sentAgents += agent
    }

    override fun loadMoreMessages() = Unit

    override fun archiveSession(session: SessionState) {
        archiveRequests += session.id
    }

    override fun renameSession(session: SessionState, title: String) = Unit

    override suspend fun cachedSessionsForProject(worktree: String, limit: Int?): List<SessionState> {
        if (limit != null) {
            cachedRequestLimits += limit
        }
        return projectSessions[worktree].orEmpty().let {
            if (limit == null) it else it.take(limit)
        }
    }

    override suspend fun sessionsForProject(worktree: String, limit: Int?): List<SessionState> {
        sessionRequests += worktree
        if (limit != null) {
            sessionRequestLimits += limit
        }
        if (failSessionRequests) {
            throw IllegalStateException("fail")
        }
        return projectSessions[worktree].orEmpty().let {
            if (limit == null) it else it.take(limit)
        }
    }
}
