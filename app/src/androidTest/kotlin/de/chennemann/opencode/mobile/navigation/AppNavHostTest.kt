package de.chennemann.opencode.mobile.navigation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.chennemann.opencode.mobile.domain.session.CommandState
import de.chennemann.opencode.mobile.domain.session.MessageState
import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.domain.session.ServerState
import de.chennemann.opencode.mobile.domain.session.SessionServiceApi
import de.chennemann.opencode.mobile.domain.session.SessionState
import de.chennemann.opencode.mobile.domain.session.SessionUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class AppNavHostTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var service: FakeSessionServiceApi

    private val sessionHome = SessionState(
        id = "session-home",
        title = "Conversation Home",
        version = "v1",
        directory = "/workspace/demo",
        updatedAt = 1L,
    )

    private val sessionTarget = SessionState(
        id = "session-target",
        title = "Resume Session",
        version = "v1",
        directory = "/workspace/demo",
        updatedAt = 2L,
    )

    private val testModule = module {
        single<SessionServiceApi> { service }
    }

    @Before
    fun setUp() {
        service = FakeSessionServiceApi(sessionHome, sessionTarget)
        loadKoinModules(testModule)
    }

    @After
    fun tearDown() {
        unloadKoinModules(testModule)
    }

    @Test
    fun handles_manage_navigation_events_and_back_stack_transitions() {
        compose.setContent {
            AppNavHost()
        }

        compose.onNodeWithText("Conversation Home").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("Workspace Hub").assertIsDisplayed()

        compose.onNodeWithText("Logs").performClick()
        compose.onNodeWithText("Application Logs").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Workspace Hub").assertIsDisplayed()

        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Conversation Home").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("Workspace Hub").assertIsDisplayed()

        compose.onNodeWithText("Resume Session").performClick()
        compose.onAllNodesWithText("Workspace Hub").assertCountEquals(0)
        compose.onNodeWithText("Resume Session").assertIsDisplayed()
    }
}

private class FakeSessionServiceApi(
    home: SessionState,
    target: SessionState,
) : SessionServiceApi {
    private val project = ProjectState(
        id = "project-demo",
        worktree = home.directory,
        name = "Demo",
        favorite = true,
    )

    private val sessions = listOf(target, home)

    private val flow = MutableStateFlow(
        SessionUiState(
            url = "http://127.0.0.1:4096",
            discovered = null,
            status = ServerState.Connected("v1"),
            projects = listOf(project),
            selectedProject = project.worktree,
            commands = emptyList<CommandState>(),
            sessions = sessions,
            activeSessions = sessions,
            focusedSession = home,
            focusedMessages = emptyList<MessageState>(),
            canLoadMoreMessages = false,
            loadingMoreMessages = false,
            loadingProjects = false,
            loadingSessions = false,
            sessionRecentOnly = false,
            message = null,
        )
    )

    override val state: StateFlow<SessionUiState> = flow

    override fun start(scope: CoroutineScope) {}

    override fun updateUrl(value: String) {
        flow.value = flow.value.copy(url = value)
    }

    override fun useDiscovered() {}

    override fun refresh() {}

    override fun selectProject(worktree: String) {
        flow.value = flow.value.copy(selectedProject = worktree)
    }

    override fun toggleProjectFavorite(worktree: String) {}

    override fun removeProject(worktree: String) {}

    override fun toggleSessionQuickPin(session: SessionState, systemPinned: Boolean) {}

    override suspend fun createSessionAndFocus(worktree: String): Boolean {
        val next = sessions.firstOrNull { it.directory == worktree } ?: return false
        flow.value = flow.value.copy(
            selectedProject = worktree,
            focusedSession = next,
        )
        return true
    }

    override fun openSession(session: SessionState) {
        flow.value = flow.value.copy(
            selectedProject = session.directory,
            focusedSession = session,
        )
    }

    override fun send(text: String, agent: String) {}

    override fun loadMoreMessages() {}

    override fun archiveSession(session: SessionState) {}

    override fun renameSession(session: SessionState, title: String) {}

    override suspend fun cachedSessionsForProject(worktree: String, limit: Int?): List<SessionState> {
        return sessions.filter { it.directory == worktree }.let {
            if (limit == null) it else it.take(limit)
        }
    }

    override suspend fun sessionsForProject(worktree: String, limit: Int?): List<SessionState> {
        return cachedSessionsForProject(worktree, limit)
    }
}
