package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.connection.ConnectionState
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentSelector
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.ChatActions
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.domain.orchestration.StartTurnResult
import de.chennemann.agentic.domain.orchestration.ThreadActions
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.EnvironmentClientConfig
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.OrchestrationProject
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelIntentTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `view model maps UI intents to local state and domain actions`() = runTest(dispatcher) {
        val repository = FakeOrchestrationRepository()
        val threadActions = RecordingThreadActions(repository)
        val controller = FakeConnectionController()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = controller,
            environmentService = EnvironmentSelector {},
            threads = threadActions,
            chat = NoOpChatActions(),
        )

        viewModel.onEvent(ChatUiEvent.DraftChanged("hello"))
        viewModel.onEvent(ChatUiEvent.ProjectSelected("project-2"))
        viewModel.onEvent(ChatUiEvent.ConnectionRetryRequested)
        advanceUntilIdle()

        assertEquals("hello", viewModel.state.value.composer.draft)
        assertEquals(listOf("project-2"), threadActions.selectedProjects)
        assertTrue(controller.woken)
    }

    @Test
    fun `project and thread picker switches projects and focuses a shell thread`() = runTest(dispatcher) {
        val repository = FakeOrchestrationRepository().apply {
            shell.value = ProjectionState(
                value = pickerShell(),
                sequence = 7,
                source = ProjectionSource.LIVE,
                synchronized = true,
            )
            selectedProjectId.value = "project-1"
        }
        val threadActions = RecordingThreadActions(repository)
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = threadActions,
            chat = NoOpChatActions(),
        )
        advanceUntilIdle()

        assertEquals(listOf("project-1", "project-2"), viewModel.state.value.threadPicker.projects.map { it.id })
        assertEquals(listOf("thread-1"), viewModel.state.value.threadPicker.threads.map { it.id })
        assertEquals(listOf("thread-settled"), viewModel.state.value.threadPicker.settledThreads.map { it.id })
        assertEquals(false, viewModel.state.value.threadPicker.showSettled)
        assertEquals(
            listOf("project-2", "project-1"),
            viewModel.state.value.composer.quickSwitchProjects.map { it.id },
        )

        viewModel.onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROJECT_THREAD))
        viewModel.onEvent(ChatUiEvent.SettledThreadsVisibilityChanged(true))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.threadPicker.showSettled)

        viewModel.onEvent(ChatUiEvent.ProjectSelected("project-2"))
        advanceUntilIdle()

        assertEquals(ChatPickerUi.PROJECT_THREAD, viewModel.state.value.activePicker)
        assertEquals("project-2", viewModel.state.value.threadPicker.selectedProjectId)
        assertEquals(listOf("thread-2"), viewModel.state.value.threadPicker.threads.map { it.id })

        viewModel.onEvent(ChatUiEvent.ThreadSelected("thread-2"))
        advanceUntilIdle()

        assertEquals(listOf("thread-2"), threadActions.selectedThreads)
        assertEquals(null, viewModel.state.value.activePicker)
        assertEquals("thread-2", viewModel.state.value.threadId)
        assertEquals("Thread Two", viewModel.state.value.title)
        assertTrue(viewModel.state.value.threadPicker.loading)

        viewModel.onEvent(ChatUiEvent.ProjectQuickSwitchRequested("project-1"))
        advanceUntilIdle()

        assertEquals(listOf("project-2", "project-1"), threadActions.selectedProjects)
        assertEquals(listOf("thread-2", "thread-1"), threadActions.selectedThreads)
        assertEquals("project-1", viewModel.state.value.threadPicker.selectedProjectId)
        assertEquals("thread-1", viewModel.state.value.threadId)

        viewModel.onEvent(ChatUiEvent.ProjectThreadsRequested("project-2"))
        advanceUntilIdle()

        assertEquals(ChatPickerUi.PROJECT_THREAD, viewModel.state.value.activePicker)
        assertEquals("project-2", viewModel.state.value.threadPicker.selectedProjectId)
        assertEquals(listOf("thread-2"), viewModel.state.value.threadPicker.threads.map { it.id })
    }

    @Test
    fun `quick switch shows five recent projects with unsettled threads`() = runTest(dispatcher) {
        val repository = FakeOrchestrationRepository().apply {
            shell.value = ProjectionState(
                value = recentProjectsShell(),
                sequence = 8,
                source = ProjectionSource.LIVE,
                synchronized = true,
            )
        }
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("project-8", "project-6", "project-5", "project-4", "project-3"),
            viewModel.state.value.composer.quickSwitchProjects.map { it.id },
        )
    }
}

private class FakeConnectionController : ConnectionController {
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Live)
    var woken = false

    override fun wake() {
        woken = true
    }
}

private class RecordingThreadActions(
    private val repository: FakeOrchestrationRepository,
) : ThreadActions {
    val selectedProjects = mutableListOf<String?>()
    val selectedThreads = mutableListOf<String?>()

    override suspend fun selectProject(projectId: String?) {
        selectedProjects += projectId
        repository.selectedProjectId.value = projectId
        repository.focusedThreadId.value = null
    }

    override suspend fun selectThread(threadId: String?) {
        selectedThreads += threadId
        repository.focusedThreadId.value = threadId
    }

    override suspend fun rename(
        threadId: String,
        title: String,
        modelSelection: ModelSelection,
    ) = DispatchResult(1)

    override suspend fun archive(threadId: String) = DispatchResult(1)

    override suspend fun unarchive(threadId: String) = DispatchResult(1)
}

private class NoOpChatActions : ChatActions {
    override suspend fun startTurn(
        threadId: String?,
        projectId: String,
        prompt: String,
        modelSelection: ModelSelection,
        interactionMode: String,
        runtimeMode: String,
    ) = StartTurnResult(DispatchResult(1), threadId ?: "new-thread")

    override suspend fun interrupt(
        threadId: String,
        turnId: String,
    ) = DispatchResult(1)

    override suspend fun respondToApproval(
        threadId: String,
        requestId: String,
        decision: String,
    ) = DispatchResult(1)

    override suspend fun respondToUserInput(
        threadId: String,
        requestId: String,
        answers: JsonObject,
    ) = DispatchResult(1)

    override suspend fun setInteractionMode(
        threadId: String,
        interactionMode: String,
    ) = DispatchResult(1)

    override suspend fun setRuntimeMode(
        threadId: String,
        runtimeMode: String,
    ) = DispatchResult(1)
}

private class FakeViewModelEnvironmentRepository : EnvironmentRepository {
    private val environment = SavedEnvironment(
        id = "environment",
        label = "Environment",
        baseUrl = "https://example.test/",
        platformOs = "linux",
        platformArch = "x64",
        serverVersion = "1",
        active = true,
        lastConnectedAt = null,
    )
    override val environments: StateFlow<List<SavedEnvironment>> = MutableStateFlow(listOf(environment))
    override val activeEnvironment: StateFlow<SavedEnvironment?> = MutableStateFlow(environment)

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

private class FakeOrchestrationRepository : OrchestrationRepository {
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
    ) = Unit

    override suspend fun setShellSnapshot(
        environmentId: String,
        snapshot: OrchestrationShellSnapshot,
        source: ProjectionSource,
    ) = Unit

    override suspend fun applyShellItem(
        environmentId: String,
        item: OrchestrationShellStreamItem,
    ): Reduction<ProjectionState<OrchestrationShellSnapshot>> = Reduction.Ignored(shell.value)

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
    ) = Unit

    override suspend fun applyThreadItem(
        environmentId: String,
        item: OrchestrationThreadStreamItem,
    ): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>> = Reduction.Ignored(focusedThread.value)

    override suspend fun clearEnvironment(environmentId: String) = Unit
}

private fun pickerShell() = OrchestrationShellSnapshot(
    projects = listOf(
        OrchestrationProject(
            id = "project-1",
            title = "Project One",
            workspaceRoot = "/workspace/one",
            createdAt = "2026-07-29T10:00:00Z",
            updatedAt = "2026-07-29T10:00:00Z",
        ),
        OrchestrationProject(
            id = "project-2",
            title = "Project Two",
            workspaceRoot = "/workspace/two",
            createdAt = "2026-07-29T10:00:00Z",
            updatedAt = "2026-07-29T10:00:00Z",
        ),
    ),
    threads = listOf(
        OrchestrationThreadShell(
            id = "thread-1",
            projectId = "project-1",
            title = "Thread One",
            createdAt = "2026-07-29T10:00:00Z",
            updatedAt = "2026-07-29T10:01:00Z",
        ),
        OrchestrationThreadShell(
            id = "thread-2",
            projectId = "project-2",
            title = "Thread Two",
            createdAt = "2026-07-29T10:00:00Z",
            updatedAt = "2026-07-29T10:02:00Z",
        ),
        OrchestrationThreadShell(
            id = "thread-settled",
            projectId = "project-1",
            title = "Settled Thread",
            createdAt = "2026-07-29T10:00:00Z",
            updatedAt = "2026-07-29T10:03:00Z",
            settledAt = "2026-07-29T10:04:00Z",
        ),
    ),
    snapshotSequence = 7,
    updatedAt = "2026-07-29T10:02:00Z",
)

private fun recentProjectsShell(): OrchestrationShellSnapshot = OrchestrationShellSnapshot(
    projects = (1..8).map { index ->
        OrchestrationProject(
            id = "project-$index",
            title = "Project $index",
            workspaceRoot = "/workspace/$index",
            createdAt = "2026-07-29T10:00:00Z",
            updatedAt = "2026-07-29T10:${index.toString().padStart(2, '0')}:00Z",
        )
    },
    threads = (1..8).map { index ->
        OrchestrationThreadShell(
            id = "thread-$index",
            projectId = "project-$index",
            title = "Thread $index",
            createdAt = "2026-07-29T10:00:00Z",
            updatedAt = "2026-07-29T10:${index.toString().padStart(2, '0')}:00Z",
            settledAt = if (index == 8) "2026-07-29T11:00:00Z" else null,
            settledOverride = when (index) {
                7 -> "settled"
                8 -> "active"
                else -> null
            },
        )
    },
    snapshotSequence = 8,
    updatedAt = "2026-07-29T11:00:00Z",
)
