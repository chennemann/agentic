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
import de.chennemann.agentic.domain.preferences.ComposerDraftRepository
import de.chennemann.agentic.domain.voice.GroqApiKeyStore
import de.chennemann.agentic.domain.voice.VoiceInputService
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.EnvironmentClientConfig
import de.chennemann.agentic.t3.contract.EnvironmentPlatform
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentCapabilities
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.LatestTurn
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.OrchestrationActivity
import de.chennemann.agentic.t3.contract.OrchestrationMessage
import de.chennemann.agentic.t3.contract.OrchestrationProject
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetail
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ProviderInstance
import de.chennemann.agentic.t3.contract.ProviderModel
import de.chennemann.agentic.t3.contract.ProviderOptionSelection
import de.chennemann.agentic.t3.contract.PortableJson
import de.chennemann.agentic.t3.contract.ServerAuthDescriptor
import de.chennemann.agentic.t3.contract.ThreadSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
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
            mappingDispatcher = dispatcher,
        )

        viewModel.onEvent(ChatUiEvent.DraftChanged("hello"))
        viewModel.onEvent(ChatUiEvent.ProjectSelected("project-2"))
        viewModel.onEvent(ChatUiEvent.ConnectionRetryRequested)
        advanceUntilIdle()

        assertEquals("hello", viewModel.draft.value)
        assertEquals(listOf("project-2"), threadActions.selectedProjects)
        assertTrue(controller.woken)
    }

    @Test
    fun `saving Groq key enables recording and transcription fills draft`() = runTest(dispatcher) {
        val apiKeys = FakeGroqApiKeyStore()
        val voiceInput = FakeVoiceInputService("transcribed prompt")
        val repository = FakeOrchestrationRepository()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            groqApiKeys = apiKeys,
            voiceInput = voiceInput,
        )

        viewModel.onEvent(ChatUiEvent.GroqSettingsRequested)
        viewModel.onEvent(ChatUiEvent.GroqApiKeySaved("  gsk_test  "))
        advanceUntilIdle()

        assertEquals("gsk_test", apiKeys.apiKey)
        assertEquals(true, viewModel.state.value.composer.voiceInputAvailable)
        assertEquals(false, viewModel.state.value.groqSettings.dialogVisible)

        viewModel.onEvent(ChatUiEvent.VoiceInputPressed)
        advanceUntilIdle()
        assertEquals(1, voiceInput.starts)
        assertEquals(
            VoiceInputStatusUi.RECORDING,
            viewModel.state.value.composer.voiceInputStatus,
        )

        viewModel.onEvent(ChatUiEvent.VoiceInputPressed)
        advanceUntilIdle()

        assertEquals(1, voiceInput.transcriptions)
        assertEquals("transcribed prompt", viewModel.draft.value)
        assertEquals(
            VoiceInputStatusUi.IDLE,
            viewModel.state.value.composer.voiceInputStatus,
        )
    }

    @Test
    fun `leaving foreground cancels active voice recording`() = runTest(dispatcher) {
        val apiKeys = FakeGroqApiKeyStore()
        val voiceInput = FakeVoiceInputService("unused")
        val repository = FakeOrchestrationRepository()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            groqApiKeys = apiKeys,
            voiceInput = voiceInput,
        )
        viewModel.onEvent(ChatUiEvent.GroqApiKeySaved("gsk_test"))
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.VoiceInputPressed)
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.VoiceInputCancelled)
        advanceUntilIdle()

        assertEquals(1, voiceInput.cancellations)
        assertEquals(
            VoiceInputStatusUi.IDLE,
            viewModel.state.value.composer.voiceInputStatus,
        )
        assertEquals(
            "Recording stopped because the app left the foreground.",
            viewModel.state.value.composer.errorMessage,
        )
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
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(listOf("project-1", "project-2"), viewModel.state.value.threadPicker.projects.map { it.id })
        assertEquals(listOf("thread-1"), viewModel.state.value.threadPicker.threads.map { it.id })
        assertEquals(listOf("thread-settled"), viewModel.state.value.threadPicker.settledThreads.map { it.id })
        assertEquals(false, viewModel.state.value.threadPicker.showSettled)
        assertEquals(
            listOf("project-1", "project-2"),
            viewModel.state.value.composer.quickSwitchProjects.map { it.id },
        )
        val timelineBeforeTyping = viewModel.state.value.timeline
        val projectsBeforeTyping = viewModel.state.value.composer.quickSwitchProjects
        val pickerBeforeTyping = viewModel.state.value.threadPicker

        viewModel.onEvent(ChatUiEvent.DraftChanged("typing stays local"))
        advanceUntilIdle()

        assertSame(timelineBeforeTyping, viewModel.state.value.timeline)
        assertSame(projectsBeforeTyping, viewModel.state.value.composer.quickSwitchProjects)
        assertSame(pickerBeforeTyping, viewModel.state.value.threadPicker)

        viewModel.onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROJECT_THREAD))
        viewModel.onEvent(ChatUiEvent.SettledThreadsVisibilityChanged(true))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.threadPicker.showSettled)

        viewModel.onEvent(ChatUiEvent.ThreadSettleRequested("thread-1"))
        viewModel.onEvent(ChatUiEvent.ThreadUnsettleRequested("thread-settled"))
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), threadActions.settledThreads)
        assertEquals(listOf("thread-settled"), threadActions.unsettledThreads)

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
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(
            listOf("project-3", "project-4", "project-5", "project-6", "project-8"),
            viewModel.state.value.composer.quickSwitchProjects.map { it.id },
        )
    }

    @Test
    fun `quick switch marks a completed unopened thread unread until selected`() = runTest(dispatcher) {
        val runningSession = ThreadSession(
            threadId = "thread-1",
            status = "running",
            updatedAt = "2026-07-29T10:02:00Z",
        )
        val initialShell = pickerShell().copy(
            threads = pickerShell().threads.map {
                if (it.id == "thread-1") it.copy(session = runningSession) else it
            },
        )
        val repository = FakeOrchestrationRepository().apply {
            shell.value = ProjectionState(
                value = initialShell,
                sequence = 7,
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
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        repository.shell.value = repository.shell.value.copy(
            value = initialShell.copy(
                threads = initialShell.threads.map {
                    if (it.id == "thread-1") {
                        it.copy(
                            session = runningSession.copy(
                                status = "idle",
                                updatedAt = "2026-07-29T10:03:00Z",
                            ),
                            updatedAt = "2026-07-29T10:03:00Z",
                        )
                    } else {
                        it
                    }
                },
            ),
            sequence = 8,
        )
        advanceUntilIdle()

        assertEquals(
            1,
            viewModel.state.value.composer.quickSwitchProjects
                .first { it.id == "project-1" }
                .unreadCount,
        )

        repository.focusedThreadId.value = "thread-1"
        advanceUntilIdle()

        assertEquals(
            0,
            viewModel.state.value.composer.quickSwitchProjects
                .first { it.id == "project-1" }
                .unreadCount,
        )
    }

    @Test
    fun `message submission preserves inherited model options and visible thread modes`() = runTest(dispatcher) {
        val selection = ModelSelection(
            instanceId = "provider",
            model = "model",
            options = listOf(ProviderOptionSelection("effort", JsonPrimitive("high"))),
        )
        val repository = submissionRepository(selection)
        val chat = NoOpChatActions()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = chat,
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.DraftChanged("send this"))
        viewModel.onEvent(ChatUiEvent.MessageSubmitted)
        viewModel.onEvent(ChatUiEvent.MessageSubmitted)
        advanceUntilIdle()

        val call = chat.startTurnCalls.single()
        assertEquals("thread-1", call.threadId)
        assertEquals("project-1", call.projectId)
        assertEquals("send this", call.prompt)
        assertEquals(selection, call.modelSelection)
        assertEquals("plan", call.interactionMode)
        assertEquals("full-access", call.runtimeMode)
        assertEquals("", viewModel.draft.value)
        assertEquals(false, viewModel.state.value.composer.sending)
        assertEquals(null, viewModel.state.value.composer.errorMessage)
    }

    @Test
    fun `provider option selection is sent while unknown inherited options are preserved`() = runTest(dispatcher) {
        val selection = ModelSelection(
            instanceId = "provider",
            model = "model",
            options = listOf(
                ProviderOptionSelection("effort", JsonPrimitive("high")),
                ProviderOptionSelection("future-option", JsonPrimitive("keep-me")),
            ),
        )
        val repository = submissionRepository(selection)
        val chat = NoOpChatActions()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = chat,
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val option = viewModel.state.value.composer.providerOptions.single() as ProviderOptionUi.Select
        assertEquals("high", option.selectedValueId)
        viewModel.onEvent(ChatUiEvent.ProviderSelectOptionSelected("effort", "ultra"))
        viewModel.onEvent(ChatUiEvent.DraftChanged("use more reasoning"))
        viewModel.onEvent(ChatUiEvent.MessageSubmitted)
        advanceUntilIdle()

        assertEquals(
            listOf(
                ProviderOptionSelection("effort", JsonPrimitive("ultra")),
                ProviderOptionSelection("future-option", JsonPrimitive("keep-me")),
            ),
            chat.startTurnCalls.single().modelSelection.options,
        )
    }

    @Test
    fun `new thread submission defaults to full access`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model")).apply {
            focusedThreadId.value = null
            focusedThread.value = ProjectionState()
        }
        val chat = NoOpChatActions()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = chat,
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.DraftChanged("start a task"))
        viewModel.onEvent(ChatUiEvent.MessageSubmitted)
        advanceUntilIdle()

        assertEquals("full-access", chat.startTurnCalls.single().runtimeMode)
    }

    @Test
    fun `structured user input options map without crashing the timeline`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val current = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(
                thread = current.thread.copy(
                    activities = listOf(
                        OrchestrationActivity(
                            id = "input-activity",
                            kind = "user-input.requested",
                            tone = "info",
                            summary = "Choose an approach",
                            payload = PortableJson.parseToJsonElement(
                                """
                                {
                                  "requestId": "request-1",
                                  "questions": [
                                    {
                                      "id": "approach",
                                      "question": "How should this work?",
                                      "options": [
                                        {
                                          "label": "Keep current behavior",
                                          "description": "Make no workflow changes."
                                        },
                                        {
                                          "id": "replace",
                                          "label": "Replace it"
                                        },
                                        "Decide later"
                                      ]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            ).jsonObject,
                            createdAt = "2026-07-29T10:00:00Z",
                        ),
                    ),
                ),
            ),
        )
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val activity = viewModel.state.value.timeline.single() as ChatTimelineItemUi.Activity
        val input = activity.value as ChatActivityUi.UserInput
        val question = input.request.questions.single()
        assertEquals("How should this work?", question.label)
        assertEquals(
            listOf(
                UserInputOptionUi(
                    id = "Keep current behavior",
                    label = "Keep current behavior",
                    description = "Make no workflow changes.",
                ),
                UserInputOptionUi(id = "replace", label = "Replace it"),
                UserInputOptionUi(id = "Decide later", label = "Decide later"),
            ),
            question.options,
        )
    }

    @Test
    fun `tool lifecycle rows collapse into a command-aware group`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val current = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(
                thread = current.thread.copy(
                    activities = listOf(
                        OrchestrationActivity(
                            id = "plan-updated",
                            kind = "task.progress",
                            tone = "thinking",
                            summary = "Plan updated",
                            payload = JsonObject(emptyMap()),
                            createdAt = "2026-07-29T09:59:00Z",
                        ),
                        toolActivity(
                            id = "tool-1-update",
                            kind = "tool.updated",
                            status = "inProgress",
                            callId = "call-1",
                            command = "pwsh -Command './gradlew test'",
                        ),
                        toolActivity(
                            id = "tool-1-complete",
                            kind = "tool.completed",
                            status = "completed",
                            callId = "call-1",
                            command = "pwsh -Command './gradlew test'",
                        ),
                        toolActivity(
                            id = "tool-2-update",
                            kind = "tool.updated",
                            status = "inProgress",
                            callId = "call-2",
                            command = "git status --short",
                        ),
                    ),
                ),
            ),
        )
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val group = viewModel.state.value.timeline.single() as ChatTimelineItemUi.ToolGroup
        assertEquals(3, group.activities.size)
        assertEquals(
            ActivityStatusUi.COMPLETED,
            group.activities.first { it.summary == "Plan updated" }.status,
        )
        assertEquals(
            ActivityStatusUi.COMPLETED,
            group.activities.first { it.subtitle == "./gradlew test" }.status,
        )
        assertEquals(
            ActivityStatusUi.RUNNING,
            group.activities.first { it.subtitle == "git status --short" }.status,
        )
    }

    @Test
    fun `tool groups stay within their turn and precede the assistant message`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val current = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(
                thread = current.thread.copy(
                    messages = listOf(
                        orchestrationMessage(
                            id = "user-1",
                            turnId = "turn-1",
                            role = "user",
                            text = "First request",
                            createdAt = "2026-07-29T10:00:00Z",
                        ),
                        orchestrationMessage(
                            id = "assistant-1",
                            turnId = "turn-1",
                            role = "assistant",
                            text = "First answer",
                            createdAt = "2026-07-29T10:02:00Z",
                        ),
                        orchestrationMessage(
                            id = "user-2",
                            turnId = "turn-2",
                            role = "user",
                            text = "Second request",
                            createdAt = "2026-07-29T10:03:00Z",
                        ),
                        orchestrationMessage(
                            id = "assistant-2",
                            turnId = "turn-2",
                            role = "assistant",
                            text = "Second answer",
                            createdAt = "2026-07-29T10:03:30Z",
                        ),
                    ),
                    activities = listOf(
                        toolActivity(
                            id = "turn-1-tool-a",
                            kind = "tool.completed",
                            status = "completed",
                            callId = "call-1-a",
                            command = "first command",
                            turnId = "turn-1",
                            createdAt = "2026-07-29T10:01:00Z",
                        ),
                        toolActivity(
                            id = "turn-1-tool-b",
                            kind = "tool.completed",
                            status = "completed",
                            callId = "call-1-b",
                            command = "second command",
                            turnId = "turn-1",
                            createdAt = "2026-07-29T10:04:00Z",
                        ),
                        toolActivity(
                            id = "turn-2-tool",
                            kind = "tool.completed",
                            status = "completed",
                            callId = "call-2",
                            command = "third command",
                            turnId = "turn-2",
                            createdAt = "2026-07-29T10:05:00Z",
                        ),
                    ),
                ),
            ),
        )
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(
            listOf(
                "user-1",
                "tool-group:turn-1-tool-a",
                "assistant-1",
                "user-2",
                "tool-group:turn-2-tool",
                "assistant-2",
            ),
            viewModel.state.value.timeline.map { it.id },
        )
        assertEquals(
            2,
            (viewModel.state.value.timeline[1] as ChatTimelineItemUi.ToolGroup).activities.size,
        )
    }

    @Test
    fun `latest turn changes only include files from its ready checkpoint`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val current = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(
                thread = current.thread.copy(
                    latestTurn = LatestTurn(
                        turnId = "turn-2",
                        state = "completed",
                        requestedAt = "2026-07-29T10:00:00Z",
                        completedAt = "2026-07-29T10:01:00Z",
                    ),
                    checkpoints = listOf(
                        turnDiffCheckpoint(
                            turnId = "turn-1",
                            files = """[{"path":"old.kt","kind":"modified","additions":1,"deletions":1}]""",
                        ),
                        turnDiffCheckpoint(
                            turnId = "turn-2",
                            files = """
                                [
                                  {"path":"z.kt","kind":"modified","additions":4,"deletions":1},
                                  {"path":"a.kt","kind":"added","additions":10,"deletions":0}
                                ]
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals("turn-2", viewModel.state.value.latestTurnChanges.turnId)
        assertEquals(
            listOf(
                ChangedFileUi("a.kt", "added", additions = 10, deletions = 0),
                ChangedFileUi("z.kt", "modified", additions = 4, deletions = 1),
            ),
            viewModel.state.value.latestTurnChanges.files,
        )
    }

    @Test
    fun `message submission failure restores composer and displays the rejection`() = runTest(dispatcher) {
        val selection = ModelSelection("provider", "model")
        val repository = submissionRepository(selection)
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(startTurnFailure = IllegalStateException("Turn rejected")),
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.DraftChanged("keep this"))
        viewModel.onEvent(ChatUiEvent.MessageSubmitted)
        advanceUntilIdle()

        assertEquals("keep this", viewModel.draft.value)
        assertEquals(false, viewModel.state.value.composer.sending)
        assertEquals("Turn rejected", viewModel.state.value.composer.errorMessage)

        viewModel.onEvent(ChatUiEvent.DraftChanged("retry"))
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.composer.errorMessage)
    }

    @Test
    fun `drafts are isolated by thread when switching between conversations`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.DraftChanged("draft for thread one"))
        viewModel.onEvent(ChatUiEvent.ThreadSelected("thread-2"))
        advanceUntilIdle()

        assertEquals("", viewModel.draft.value)

        viewModel.onEvent(ChatUiEvent.DraftChanged("draft for thread two"))
        viewModel.onEvent(ChatUiEvent.ThreadSelected("thread-1"))
        advanceUntilIdle()

        assertEquals("draft for thread one", viewModel.draft.value)
    }

    @Test
    fun `draft survives view model recreation`() = runTest(dispatcher) {
        val drafts = FakeComposerDraftRepository()
        val firstRepository = submissionRepository(ModelSelection("provider", "model"))
        val firstViewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = firstRepository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(firstRepository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            composerDrafts = drafts,
        )
        advanceUntilIdle()

        firstViewModel.onEvent(ChatUiEvent.DraftChanged("survive app replacement"))
        advanceUntilIdle()

        val recreatedRepository = submissionRepository(ModelSelection("provider", "model"))
        val recreatedViewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = recreatedRepository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(recreatedRepository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            composerDrafts = drafts,
        )
        advanceUntilIdle()

        assertEquals("survive app replacement", recreatedViewModel.draft.value)
    }

    @Test
    fun `rapid typing coalesces persistence into one database write`() = runTest(dispatcher) {
        val drafts = FakeComposerDraftRepository()
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            composerDrafts = drafts,
        )
        advanceUntilIdle()

        repeat(100) { index ->
            viewModel.onEvent(ChatUiEvent.DraftChanged("draft $index"))
        }
        runCurrent()

        assertEquals(emptyList<String>(), drafts.writes.map { it.third })
        assertEquals("draft 99", viewModel.draft.value)

        advanceTimeBy(299)
        runCurrent()
        assertEquals(emptyList<String>(), drafts.writes.map { it.third })

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("draft 99"), drafts.writes.map { it.third })
    }

    @Test
    fun `switching threads flushes a pending draft without waiting for the debounce`() = runTest(dispatcher) {
        val drafts = FakeComposerDraftRepository()
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            composerDrafts = drafts,
        )
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.DraftChanged("flush before switching"))
        viewModel.onEvent(ChatUiEvent.ThreadSelected("thread-2"))
        runCurrent()

        assertEquals(listOf("flush before switching"), drafts.writes.map { it.third })

        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("flush before switching"), drafts.writes.map { it.third })
    }

    @Test
    fun `typing does not emit a new whole screen state`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            composerDrafts = FakeComposerDraftRepository(),
        )
        advanceUntilIdle()
        val screenStateBeforeTyping = viewModel.state.value

        repeat(100) { index ->
            viewModel.onEvent(ChatUiEvent.DraftChanged("isolated draft $index"))
        }
        runCurrent()

        assertSame(screenStateBeforeTyping, viewModel.state.value)
        assertEquals("isolated draft 99", viewModel.draft.value)
    }
}

private class FakeComposerDraftRepository : ComposerDraftRepository {
    private val drafts = mutableMapOf<Pair<String, String>, MutableStateFlow<String>>()
    val writes = mutableListOf<Triple<String, String, String>>()

    override fun observe(
        environmentId: String,
        threadId: String,
    ): Flow<String> = drafts.getOrPut(environmentId to threadId) { MutableStateFlow("") }

    override suspend fun setDraft(
        environmentId: String,
        threadId: String,
        draft: String,
    ) {
        writes += Triple(environmentId, threadId, draft)
        drafts.getOrPut(environmentId to threadId) { MutableStateFlow("") }.value = draft
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
    val settledThreads = mutableListOf<String>()
    val unsettledThreads = mutableListOf<String>()

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

    override suspend fun settle(threadId: String): DispatchResult {
        settledThreads += threadId
        return DispatchResult(1)
    }

    override suspend fun unsettle(threadId: String): DispatchResult {
        unsettledThreads += threadId
        return DispatchResult(1)
    }
}

private class NoOpChatActions(
    private val startTurnFailure: Throwable? = null,
) : ChatActions {
    val startTurnCalls = mutableListOf<StartTurnCall>()

    override suspend fun startTurn(
        threadId: String?,
        projectId: String,
        prompt: String,
        modelSelection: ModelSelection,
        interactionMode: String,
        runtimeMode: String,
    ): StartTurnResult {
        startTurnFailure?.let { throw it }
        startTurnCalls += StartTurnCall(
            threadId,
            projectId,
            prompt,
            modelSelection,
            interactionMode,
            runtimeMode,
        )
        return StartTurnResult(DispatchResult(1), threadId ?: "new-thread")
    }

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

private data class StartTurnCall(
    val threadId: String?,
    val projectId: String,
    val prompt: String,
    val modelSelection: ModelSelection,
    val interactionMode: String,
    val runtimeMode: String,
)

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

private fun submissionRepository(selection: ModelSelection) = FakeOrchestrationRepository().apply {
    clientConfig.value = ProjectionState(
        value = EnvironmentClientConfig(
            environment = ExecutionEnvironmentDescriptor(
                environmentId = "environment",
                label = "Environment",
                platform = EnvironmentPlatform("linux", "x64"),
                serverVersion = "1",
                capabilities = ExecutionEnvironmentCapabilities(portableClientProtocol = 1),
            ),
            auth = ServerAuthDescriptor(
                policy = "remote-reachable",
                bootstrapMethods = listOf("one-time-token"),
                sessionMethods = listOf("bearer-access-token"),
                sessionCookieName = "t3-session",
            ),
            providers = listOf(
                ProviderInstance(
                    instanceId = "provider",
                    displayName = "Provider",
                    models = listOf(
                        ProviderModel(
                            slug = "model",
                            name = "Model",
                            capabilities = PortableJson.parseToJsonElement(
                                """
                                {
                                  "optionDescriptors": [
                                    {
                                      "id": "effort",
                                      "label": "Reasoning",
                                      "type": "select",
                                      "currentValue": "medium",
                                      "options": [
                                        {"id": "low", "label": "Low"},
                                        {"id": "high", "label": "High", "isDefault": true},
                                        {"id": "ultra", "label": "Ultra"}
                                      ]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            ).jsonObject,
                        ),
                    ),
                ),
            ),
            shellResumeCompletionMarker = true,
            threadResumeCompletionMarker = true,
            protocolVersion = 1,
        ),
        source = ProjectionSource.LIVE,
    )
    shell.value = ProjectionState(
        value = pickerShell(),
        sequence = 7,
        source = ProjectionSource.LIVE,
        synchronized = true,
    )
    selectedProjectId.value = "project-1"
    focusedThreadId.value = "thread-1"
    focusedThread.value = ProjectionState(
        value = OrchestrationThreadDetailSnapshot(
            snapshotSequence = 7,
            thread = OrchestrationThreadDetail(
                id = "thread-1",
                projectId = "project-1",
                title = "Thread One",
                modelSelection = selection,
                interactionMode = "plan",
                runtimeMode = "full-access",
                createdAt = "2026-07-29T10:00:00Z",
                updatedAt = "2026-07-29T10:01:00Z",
            ),
        ),
        sequence = 7,
        source = ProjectionSource.LIVE,
        synchronized = true,
    )
}

private fun toolActivity(
    id: String,
    kind: String,
    status: String,
    callId: String,
    command: String,
    turnId: String? = null,
    createdAt: String = "2026-07-29T10:00:00Z",
) = OrchestrationActivity(
    id = id,
    turnId = turnId,
    kind = kind,
    tone = "tool",
    summary = "Run command",
    payload = PortableJson.parseToJsonElement(
        """
        {
          "title": "Run command",
          "itemType": "command_execution",
          "status": "$status",
          "data": {
            "item": {
              "id": "$callId",
              "command": "$command"
            }
          }
        }
        """.trimIndent(),
    ).jsonObject,
    createdAt = createdAt,
)

private fun orchestrationMessage(
    id: String,
    turnId: String,
    role: String,
    text: String,
    createdAt: String,
) = OrchestrationMessage(
    id = id,
    turnId = turnId,
    role = role,
    text = text,
    createdAt = createdAt,
    updatedAt = createdAt,
)

private fun turnDiffCheckpoint(
    turnId: String,
    files: String,
): kotlinx.serialization.json.JsonElement = PortableJson.parseToJsonElement(
    """
    {
      "turnId": "$turnId",
      "status": "ready",
      "files": $files
    }
    """.trimIndent(),
)

private class FakeGroqApiKeyStore : GroqApiKeyStore {
    private val mutableConfigured = MutableStateFlow(false)

    override val configured: StateFlow<Boolean> = mutableConfigured
    var apiKey: String? = null

    override suspend fun read(): String? = apiKey

    override suspend fun write(apiKey: String) {
        this.apiKey = apiKey
        mutableConfigured.value = true
    }

    override suspend fun remove() {
        apiKey = null
        mutableConfigured.value = false
    }
}

private class FakeVoiceInputService(
    private val transcript: String,
) : VoiceInputService {
    var starts = 0
    var transcriptions = 0
    var cancellations = 0

    override fun startRecording() {
        starts += 1
    }

    override suspend fun stopAndTranscribe(): String {
        transcriptions += 1
        return transcript
    }

    override fun cancelRecording() {
        cancellations += 1
    }
}
