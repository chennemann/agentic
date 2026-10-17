package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.attachments.ImageAttachmentReader
import de.chennemann.agentic.domain.connection.ConnectionState
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentCacheActions
import de.chennemann.agentic.domain.environment.EnvironmentCacheUsage
import de.chennemann.agentic.domain.environment.CacheCategoryUsage
import de.chennemann.agentic.domain.environment.EnvironmentSelector
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.ChatActions
import de.chennemann.agentic.domain.orchestration.CommandOutbox
import de.chennemann.agentic.domain.orchestration.OutboxCommand
import de.chennemann.agentic.domain.orchestration.OutboxCommandStatus
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.ProjectActions
import de.chennemann.agentic.domain.orchestration.ProjectDestination
import de.chennemann.agentic.domain.orchestration.ProjectDestinationBrowser
import de.chennemann.agentic.domain.orchestration.ProjectDestinationListing
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.domain.orchestration.StartTurnResult
import de.chennemann.agentic.domain.orchestration.NewThreadWorkspace
import de.chennemann.agentic.domain.orchestration.ThreadActions
import de.chennemann.agentic.domain.preferences.ComposerDraftRepository
import de.chennemann.agentic.domain.preferences.InterfacePreferences
import de.chennemann.agentic.domain.preferences.InterfacePreferencesRepository
import de.chennemann.agentic.domain.preferences.AccentPreference
import de.chennemann.agentic.domain.preferences.ProjectFavoriteRepository
import de.chennemann.agentic.domain.preferences.ThemePreference
import de.chennemann.agentic.domain.sharing.PendingSharedText
import de.chennemann.agentic.domain.sharing.SharedTextImportRepository
import de.chennemann.agentic.domain.sharing.SharedTextParseResult
import de.chennemann.agentic.domain.shortcuts.DynamicShortcutPublisher
import de.chennemann.agentic.domain.shortcuts.DynamicShortcutSpec
import de.chennemann.agentic.domain.shortcuts.DefaultShortcutCoordinator
import de.chennemann.agentic.domain.shortcuts.ShortcutParseResult
import de.chennemann.agentic.domain.shortcuts.ShortcutRoute
import de.chennemann.agentic.domain.shortcuts.ShortcutRouteInbox
import de.chennemann.agentic.ui.chat.workflow.DefaultCacheAdministration
import de.chennemann.agentic.ui.chat.workflow.DefaultProjectWorkflow
import de.chennemann.agentic.ui.chat.workflow.ProjectWorkflowIntent
import de.chennemann.agentic.domain.orchestration.ProjectStarter
import de.chennemann.agentic.domain.orchestration.StartedProject
import de.chennemann.agentic.domain.orchestration.ThreadProjectionReducer
import de.chennemann.agentic.ui.chat.workflow.DefaultSharedTextWorkflow
import de.chennemann.agentic.ui.chat.workflow.DefaultShortcutWorkflow
import de.chennemann.agentic.domain.voice.GroqApiKeyStore
import de.chennemann.agentic.domain.voice.VoiceInputService
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.ServerSettings
import de.chennemann.agentic.t3.contract.EnvironmentPlatform
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentCapabilities
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.LatestTurn
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.OrchestrationActivity
import de.chennemann.agentic.t3.contract.OrchestrationEvent
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
import de.chennemann.agentic.t3.contract.ProjectScript
import de.chennemann.agentic.t3.contract.ProjectScriptIcon
import de.chennemann.agentic.t3.contract.ProposedPlan
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.ServerAuthDescriptor
import de.chennemann.agentic.t3.contract.ThreadSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelIntentTest {
    @Test
    fun `composer skills use the exact workspace catalog and uploaded context binds to server ids`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val environments = FakeViewModelEnvironmentRepository()
        val config = repository.clientConfig.value.value!!
        repository.clientConfig.value = repository.clientConfig.value.copy(value = config.copy(environment = config.environment.copy(capabilities = ExecutionEnvironmentCapabilities(attachmentUploads = true, inlineMessageContext = true, fileAttachments = de.chennemann.agentic.t3.contract.FileAttachmentCapability(5000000)))))
        val current = repository.focusedThread.value.value!!
        repository.focusedThread.value = repository.focusedThread.value.copy(value = current.copy(thread = current.thread.copy(worktreePath = "/worktree")))
        val shell = repository.shell.value.value!!
        repository.shell.value = repository.shell.value.copy(value = shell.copy(threads = shell.threads.map { if (it.id == "thread-1") it.copy(worktreePath = "/worktree") else it }))
        val rpc = RecordingComposerRpc()
        val service = de.chennemann.agentic.domain.composer.ComposerContextService(environments, ComposerTestCredentials(), repository, rpc)
        assertEquals(listOf("allowed"), service.suggestions("skill", "", "provider").map { it.value })
        assertEquals(listOf("provider" to "/worktree"), rpc.workspaces)
        val file = de.chennemann.agentic.domain.composer.pastedTextFile("payload", emptyList())
        val doc = de.chennemann.agentic.domain.composer.ComposerDocument(de.chennemann.agentic.domain.composer.composerContextReference("file", file.id, file.name), listOf(de.chennemann.agentic.domain.composer.pastedTextRecord(file)), listOf(file))
        val prepared = service.prepare("environment", doc)
        assertEquals("uploaded-file", prepared.attachments.single().id)
        assertEquals(JsonPrimitive("uploaded-file"), prepared.records.single()["attachmentId"])
        assertEquals("file", prepared.attachments.single().type)
        assertEquals("pasted-text", prepared.attachments.single().source?.get("_tag")?.let { (it as JsonPrimitive).content })
        rpc.onUpload = { (environments.activeEnvironment as MutableStateFlow).value = null }
        val error = runCatching { service.prepare("environment", doc) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("environment changed"))
    }

    @Test
    fun `composer completion replaces only the active token and restores the caret`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val actions = RecordingComposerContext()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
            composerContext = actions,
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.ComposerEdited("Review @src then test", 11, 11))
        advanceUntilIdle()
        assertEquals(listOf("path" to "src"), actions.queries)
        viewModel.onEvent(ChatUiEvent.ComposerSuggestionSelected("path:src/Main.kt"))
        advanceUntilIdle()
        assertEquals("Review [Main.kt](src/Main.kt)  then test", viewModel.draft.value)
        assertEquals(30, viewModel.state.value.composer.selectionRequest?.start)
        assertTrue(viewModel.state.value.composer.suggestions.isEmpty())
    }

    @Test
    fun `large unicode paste folds at the selection and sends an uploaded file with bound context`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val chat = NoOpChatActions()
        val actions = RecordingComposerContext()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = chat, mappingDispatcher = dispatcher,
            composerContext = actions,
        )
        advanceUntilIdle()
        val pasted = "\u65e5".repeat(12000)
        viewModel.onEvent(ChatUiEvent.DraftChanged("before replace after"))
        viewModel.onEvent(ChatUiEvent.ComposerTextPasted("before replace after", 7, 14, pasted))
        advanceUntilIdle()
        assertTrue(viewModel.draft.value.startsWith("before [pasted-text.txt](t3-context://v1/file/"))
        assertTrue(viewModel.draft.value.endsWith("  after"))
        assertEquals(1, viewModel.state.value.composer.contextItems.size)
        viewModel.onEvent(ChatUiEvent.ComposerContextRequested(de.chennemann.agentic.domain.composer.composerTokens(viewModel.draft.value).single().source))
        advanceUntilIdle()
        assertEquals(pasted, viewModel.state.value.composer.contextPreview?.text)
        viewModel.onEvent(ChatUiEvent.MessageSubmitted)
        advanceUntilIdle()
        assertEquals(pasted, actions.prepared.single().pastes.single().text)
        assertEquals(1, chat.startTurnCalls.size)
        assertEquals("", viewModel.draft.value)
    }

    @Test
    fun `unavailable upload keeps large paste editable and oversized paste preserves the draft`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.ComposerTextPasted("", 0, 0, "a".repeat(33000)))
        advanceUntilIdle()
        assertEquals(33000, viewModel.draft.value.length)
        viewModel.onEvent(ChatUiEvent.ComposerTextPasted(viewModel.draft.value, 0, 0, "b".repeat(120000)))
        advanceUntilIdle()
        assertEquals("a".repeat(33000), viewModel.draft.value)
        assertTrue(viewModel.state.value.composer.errorMessage.orEmpty().contains("limit"))
    }

    @Test
    fun `follow up tap steers by default and long press queues with reversed configuration`() = runTest(dispatcher) {
        for (behavior in de.chennemann.agentic.domain.preferences.FollowUpBehavior.entries) {
            val fixture = QueueFixture(backgroundScope)
            fixture.setRunning(true, 7)
            val snapshot = requireNotNull(fixture.repository.focusedThread.value.value)
            fixture.repository.focusedThread.value = fixture.repository.focusedThread.value.copy(value = snapshot.copy(
                thread = snapshot.thread.copy(session = ThreadSession("thread-1", "running", activeTurnId = "active", updatedAt = "now")),
            ))
            val chat = NoOpChatActions()
            val preferences = RecordingInterfacePreferencesRepository(InterfacePreferences(followUpBehavior = behavior))
            val viewModel = ChatViewModel(
                environments = fixture.environments, repository = fixture.repository,
                connection = fixture.connection, environmentService = EnvironmentSelector {},
                threads = RecordingThreadActions(fixture.repository), chat = chat, mappingDispatcher = dispatcher,
                interfacePreferences = preferences, messageQueue = fixture.queue,
            )
            runCurrent()
            viewModel.onEvent(ChatUiEvent.DraftChanged("tap"))
            runCurrent()
            viewModel.onEvent(ChatUiEvent.MessageSubmitted)
            runCurrent()
            viewModel.onEvent(ChatUiEvent.DraftChanged("hold"))
            runCurrent()
            viewModel.onEvent(ChatUiEvent.AlternateMessageSubmitted)
            runCurrent()
            val steerDefault = behavior == de.chennemann.agentic.domain.preferences.FollowUpBehavior.STEER
            assertEquals(listOf(if (steerDefault) "tap" else "hold"), chat.startTurnCalls.map { it.prompt })
            assertEquals(listOf(if (steerDefault) "hold" else "tap"), fixture.queue.messages.value.map { it.command.message.text })
            assertEquals("", viewModel.draft.value)
            viewModel.onEvent(ChatUiEvent.TurnInterruptRequested)
            runCurrent()
            assertTrue(fixture.queue.messages.value.single().paused)
            assertEquals(listOf("thread-1" to "active"), chat.interruptedTurns)
        }
    }

    @Test
    fun `queued messages wait for completion and projection acknowledgement in FIFO order off screen`() = runTest(dispatcher) {
        val fixture = QueueFixture(backgroundScope)
        fixture.setRunning(true, 7)
        fixture.enqueue("first")
        fixture.enqueue("second")
        fixture.repository.focusedThreadId.value = "thread-2"
        runCurrent()
        assertTrue(fixture.sent.isEmpty())
        fixture.setRunning(false, 8)
        runCurrent()
        assertEquals(listOf("first"), fixture.sent.map { it.message.text })
        fixture.setRunning(true, 9)
        runCurrent()
        assertEquals(1, fixture.sent.size)
        fixture.setRunning(false, 10)
        runCurrent()
        assertEquals(listOf("first", "second"), fixture.sent.map { it.message.text })
        assertTrue(fixture.queue.messages.value.isEmpty())
    }

    @Test
    fun `queue holds for offline cache environment mismatch approvals and stop`() = runTest(dispatcher) {
        val fixture = QueueFixture(backgroundScope)
        fixture.setRunning(true, 7)
        fixture.enqueue("pending")
        runCurrent()
        fixture.connectionState.value = ConnectionState.Cached
        fixture.setRunning(false, 8)
        runCurrent()
        assertTrue(fixture.sent.isEmpty())
        fixture.connectionState.value = ConnectionState.Live
        fixture.repository.shell.value = fixture.repository.shell.value.copy(source = ProjectionSource.CACHE)
        runCurrent()
        assertTrue(fixture.sent.isEmpty())
        fixture.repository.shell.value = fixture.repository.shell.value.copy(source = ProjectionSource.LIVE)
        val config = fixture.repository.clientConfig.value
        fixture.repository.clientConfig.value = config.copy(value = config.value?.copy(environment = config.value.environment.copy(environmentId = "other")))
        runCurrent()
        assertTrue(fixture.sent.isEmpty())
        fixture.repository.clientConfig.value = config
        fixture.setRunning(false, 9, approval = true)
        fixture.queue.sendNow(fixture.queue.messages.value.single().id)
        runCurrent()
        assertTrue(fixture.sent.isEmpty())
        fixture.queue.pause("environment", "thread-1")
        fixture.setRunning(false, 10)
        runCurrent()
        assertTrue(fixture.sent.isEmpty())
        fixture.queue.sendNow(fixture.queue.messages.value.single().id)
        runCurrent()
        assertEquals(1, fixture.sent.size)
    }

    @Test
    fun `queue failure retains message without resubmitting an outbox owned command`() = runTest(dispatcher) {
        val fixture = QueueFixture(backgroundScope)
        fixture.failure = IllegalStateException("Offline")
        fixture.enqueue("keep me")
        fixture.setRunning(false, 8)
        runCurrent()
        val failed = fixture.queue.messages.value.single()
        assertEquals("keep me", failed.command.message.text)
        assertTrue(failed.paused)
        assertTrue(failed.error != null)
        fixture.queue.sendNow(failed.id)
        fixture.setRunning(false, 10)
        runCurrent()
        assertEquals(1, fixture.sent.size)
        fixture.queue.remove(failed.id)
        assertTrue(fixture.queue.messages.value.isEmpty())
    }

    @Test
    fun `stop during startup omits turn id instead of using a stale completed turn`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val snapshot = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(value = snapshot.copy(thread = snapshot.thread.copy(
            latestTurn = LatestTurn("old", "completed", requestedAt = "now", completedAt = "now"),
            session = ThreadSession("thread-1", "starting", updatedAt = "now"),
        )))
        val chat = NoOpChatActions()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = chat, mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isTurnRunning)
        viewModel.onEvent(ChatUiEvent.TurnInterruptRequested)
        advanceUntilIdle()
        assertEquals(listOf("thread-1" to null), chat.interruptedTurns)
    }

    @Test
    fun `stop interrupts active session even before latest turn arrives`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val snapshot = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = snapshot.copy(thread = snapshot.thread.copy(
                latestTurn = null,
                session = ThreadSession("thread-1", "running", activeTurnId = "active-turn", updatedAt = "2026-10-04T10:00:00Z"),
            )),
        )
        val chat = NoOpChatActions()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = chat, mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isTurnRunning)
        viewModel.onEvent(ChatUiEvent.TurnInterruptRequested)
        advanceUntilIdle()
        assertEquals(listOf("thread-1" to "active-turn"), chat.interruptedTurns)
    }

    @Test
    fun `native request closures clear prompts even when older requests load afterward`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val current = requireNotNull(repository.focusedThread.value.value)
        fun activity(id: String, kind: String, requestId: String, detail: String = "") = OrchestrationActivity(
            id = id, kind = kind, tone = "info", summary = kind, createdAt = "2026-07-29T10:00:00Z",
            payload = T3Json.parseToJsonElement("""{"requestId":"$requestId","detail":"$detail","questions":[{"id":"choice","question":"Choose an approach","options":["Keep it"]}]}""").jsonObject,
        )
        val approval = activity("approval", "approval.requested", "approval-request")
        val question = activity("question", "user-input.requested", "question-request")
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(thread = current.thread.copy(messages = emptyList(), activities = listOf(approval, question))),
        )
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.timeline.filterIsInstance<ChatTimelineItemUi.Activity>().count {
            it.value is ChatActivityUi.Approval || it.value is ChatActivityUi.UserInput
        })
        fun appendNativeActivity(activity: OrchestrationActivity) {
            val sequence = requireNotNull(repository.focusedThread.value.sequence) + 1
            val event = OrchestrationEvent(
                type = "thread.activity-appended", sequence = sequence, aggregateId = current.thread.id,
                aggregateKind = "thread", eventId = "event-$sequence", commandId = "command-$sequence",
                correlationId = "correlation", occurredAt = activity.createdAt,
                payload = JsonObject(mapOf("activity" to T3Json.encodeToJsonElement(activity))),
            )
            val reduced = ThreadProjectionReducer.reduce(repository.focusedThread.value, OrchestrationThreadStreamItem.Event(event))
            repository.focusedThread.value = (reduced as Reduction.Applied).state
        }
        appendNativeActivity(activity("approved", "approval.resolved", "approval-request"))
        appendNativeActivity(activity("answered", "user-input.resolved", "question-request"))
        // A page or reordered delivery can introduce an old request after its terminal activity.
        appendNativeActivity(approval.copy(id = "older-approval"))
        appendNativeActivity(question.copy(id = "older-question"))
        advanceUntilIdle()
        assertFalse(viewModel.state.value.timeline.filterIsInstance<ChatTimelineItemUi.Activity>().any {
            it.value is ChatActivityUi.Approval || it.value is ChatActivityUi.UserInput
        })
        appendNativeActivity(activity("new-request", "approval.requested", "retry-request"))
        appendNativeActivity(activity("temporary-failure", "provider.approval.respond.failed", "retry-request", "Network interrupted"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.timeline.filterIsInstance<ChatTimelineItemUi.Activity>().any { it.value is ChatActivityUi.Approval })
        appendNativeActivity(activity("stale-failure", "provider.approval.respond.failed", "retry-request", "Unknown pending approval request"))
        advanceUntilIdle()
        assertFalse(viewModel.state.value.timeline.filterIsInstance<ChatTimelineItemUi.Activity>().any { it.value is ChatActivityUi.Approval })
    }

    @Test
    fun `new project partial success selects the project and keeps its notice visible`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val threads = RecordingThreadActions(repository)
        val starter = object : ProjectStarter {
            override suspend fun createNew(name: String) = StartedProject("created", "Project created. The initial commit failed.")
            override suspend fun useScratch() = StartedProject("scratch")
        }
        val workflow = DefaultProjectWorkflow(repository, RecordingProjectActions(), threads, starter = starter)
        workflow.accept(ProjectWorkflowIntent.RequestCreation)
        workflow.accept(ProjectWorkflowIntent.ChangeCreationKind(true))
        workflow.accept(ProjectWorkflowIntent.ChangeCreationSource("New project"))
        workflow.accept(ProjectWorkflowIntent.ConfirmCreation)

        assertEquals(listOf("created"), threads.selectedProjects)
        assertFalse(workflow.state.value.creationVisible)
        assertFalse(workflow.state.value.creationSaving)
        assertEquals(null, workflow.state.value.creationError)
        assertEquals("Project created. The initial commit failed.", workflow.state.value.creationNotice)
        workflow.accept(ProjectWorkflowIntent.DismissCreationNotice)
        assertEquals(null, workflow.state.value.creationNotice)
    }

    @Test
    fun `project favorites map from persistence and picker events write them back`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val favorites = RecordingProjectFavoriteRepository(setOf("project-1"))
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            projectFavorites = favorites,
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.threadPicker.projects.single { it.id == "project-1" }.favorite)

        viewModel.onEvent(ChatUiEvent.ProjectFavoriteChanged("project-1", false))
        advanceUntilIdle()

        assertEquals(listOf(Triple("environment", "project-1", false)), favorites.writes)
        assertFalse(viewModel.state.value.threadPicker.projects.single { it.id == "project-1" }.favorite)
    }

    @Test
    fun `terminal follows React Native workspace availability without capability advertisement`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val shell = requireNotNull(repository.shell.value.value)
        repository.shell.value = repository.shell.value.copy(
            value = shell.copy(
                projects = shell.projects.map { project ->
                    if (project.id == "project-1") {
                        project.copy(
                            scripts = listOf(
                                ProjectScript("dev", "Dev", "pnpm dev", ProjectScriptIcon.DEBUG, false),
                            ),
                        )
                    } else {
                        project
                    }
                },
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

        assertTrue(viewModel.state.value.canUseTerminal)
        assertEquals(listOf("dev"), viewModel.state.value.projectScripts.map { it.id })
    }

    @Test
    fun `project destination browsing maps choices and preserves path on guarded failure`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        repository.clientConfig.value = repository.clientConfig.value.copy(
            value = repository.clientConfig.value.value?.copy(
                settings = ServerSettings(addProjectBaseDirectory = " /srv/projects "),
            ),
        )
        val browser = RecordingProjectDestinationBrowser()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            projectWorkflow = DefaultProjectWorkflow(
                repository,
                RecordingProjectActions(),
                RecordingThreadActions(repository),
                browser,
            ),
        )

        viewModel.onEvent(ChatUiEvent.ProjectCreationRequested)
        advanceUntilIdle()
        assertEquals("/srv/projects/", browser.requests.single())
        assertEquals(listOf(ProjectDestinationUi("alpha", "/srv/alpha")), viewModel.state.value.projectCreation?.destinations)

        browser.failure = IllegalStateException("Permission denied")
        viewModel.onEvent(ChatUiEvent.ProjectCreationDestinationOpened("/srv/alpha"))
        advanceUntilIdle()

        assertEquals("/srv/projects/", viewModel.state.value.projectCreation?.source)
        assertEquals("Permission denied", viewModel.state.value.projectCreation?.errorMessage)
        assertEquals(listOf("/srv/projects/", "/srv/alpha"), browser.requests)
    }

    @Test
    fun `new view model resets workflow forms while durable shared text restores`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val environments = FakeViewModelEnvironmentRepository()
        val imports = FakeSharedTextImportRepository(PendingSharedText("durable", "restore me"))
        val drafts = FakeComposerDraftRepository()
        fun createViewModel() = ChatViewModel(
            environments = environments,
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository),
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
            projectWorkflow = DefaultProjectWorkflow(repository, RecordingProjectActions(), RecordingThreadActions(repository)),
            sharedTextWorkflow = DefaultSharedTextWorkflow(
                imports,
                environments,
                EnvironmentSelector {},
                repository,
                RecordingThreadActions(repository),
                drafts,
            ),
        )

        val first = createViewModel()
        advanceUntilIdle()
        first.onEvent(ChatUiEvent.ProjectCreationRequested)
        first.onEvent(ChatUiEvent.ProjectCreationSourceChanged("/stale/path"))
        first.onEvent(ChatUiEvent.SharedImportEnvironmentSelected("environment"))
        advanceUntilIdle()
        assertEquals("/stale/path", first.state.value.projectCreation?.source)
        assertEquals("environment", first.state.value.sharedTextImport?.environmentId)

        val recreated = createViewModel()
        advanceUntilIdle()

        assertEquals(null, recreated.state.value.projectCreation)
        assertEquals("restore me", recreated.state.value.sharedTextImport?.text)
        assertEquals(null, recreated.state.value.sharedTextImport?.environmentId)
    }

    @Test
    fun `durable outbox failure maps safely retries and acknowledgement removes it`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val outbox = ReactiveCommandOutbox(
            OutboxCommand(
                "environment",
                ClientOrchestrationCommand.ArchiveThread("command", "thread-1"),
                OutboxCommandStatus.FAILED,
                2,
                "Network unavailable",
            ),
        )
        val connection = FakeConnectionController()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = connection, environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
            commandOutbox = outbox,
        )
        advanceUntilIdle()
        val work = viewModel.state.value.durableWork.single()
        assertEquals("Update thread", work.label)
        assertEquals("thread-1", work.threadId)
        assertEquals("Network unavailable", work.errorMessage)
        assertTrue(work.awaitsReplay)

        viewModel.onEvent(ChatUiEvent.DurableWorkRetryRequested)
        assertTrue(connection.pendingCommandsRetried)
        outbox.remove("environment", "command")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.durableWork.isEmpty())
    }

    @Test
    fun `cache inspection maps protected categories and confirmation retries clear`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val cache = RecordingEnvironmentCacheActions()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
            cacheAdministration = DefaultCacheAdministration(cache),
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.CacheInspectionRequested("environment"))
        advanceUntilIdle()
        assertEquals(160L, viewModel.state.value.cacheClearance?.clearableBytes)
        assertTrue(viewModel.state.value.cacheClearance?.categories?.any { "protected" in it } == true)

        cache.failure = IllegalStateException("Clear rejected")
        viewModel.onEvent(ChatUiEvent.CacheClearConfirmed)
        advanceUntilIdle()
        assertEquals("Clear rejected", viewModel.state.value.cacheClearance?.errorMessage)
        cache.failure = null
        viewModel.onEvent(ChatUiEvent.CacheClearConfirmed)
        advanceUntilIdle()
        assertEquals(listOf("environment", "environment"), cache.cleared)
        assertTrue(viewModel.state.value.cacheClearance?.success == true)
    }

    @Test
    fun `workflow flow maps directly without a local state mirror`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val workflow = DefaultCacheAdministration(RecordingEnvironmentCacheActions())
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
            cacheAdministration = workflow,
        )
        advanceUntilIdle()

        workflow.accept(de.chennemann.agentic.ui.chat.workflow.CacheAdministrationIntent.Inspect("environment"))
        advanceUntilIdle()

        assertEquals(160L, viewModel.state.value.cacheClearance?.clearableBytes)
    }

    @Test
    fun `interface preferences map reactively and settings events use repository values`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val preferences = RecordingInterfacePreferencesRepository(
            InterfacePreferences(
                theme = ThemePreference.DARK,
                accent = AccentPreference.PINK,
                interfaceScale = 1.15f,
                codeScale = 0.9f,
            ),
        )
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
            interfacePreferences = preferences,
        )
        advanceUntilIdle()

        assertEquals(ThemePreference.DARK, viewModel.state.value.interfaceSettings.theme)
        assertEquals(AccentPreference.PINK, viewModel.state.value.interfaceSettings.accent)
        assertEquals(1.15f, viewModel.state.value.interfaceSettings.interfaceScale)
        assertEquals(0.9f, viewModel.state.value.interfaceSettings.codeScale)

        viewModel.onEvent(ChatUiEvent.ThemeSelected(ThemePreference.LIGHT))
        viewModel.onEvent(ChatUiEvent.AccentSelected(AccentPreference.TEAL))
        viewModel.onEvent(ChatUiEvent.InterfaceScaleSelected(0.9f))
        viewModel.onEvent(ChatUiEvent.CodeScaleSelected(1.15f))
        viewModel.onEvent(ChatUiEvent.InterfaceScaleSelected(9f))
        viewModel.onEvent(ChatUiEvent.CodeScaleSelected(-1f))
        advanceUntilIdle()

        assertEquals(listOf(ThemePreference.LIGHT), preferences.themes)
        assertEquals(listOf(AccentPreference.TEAL), preferences.accents)
        assertEquals(listOf(0.9f), preferences.interfaceScales)
        assertEquals(listOf(1.15f), preferences.codeScales)
        assertEquals(ThemePreference.LIGHT, viewModel.state.value.interfaceSettings.theme)
        assertEquals(AccentPreference.TEAL, viewModel.state.value.interfaceSettings.accent)
        assertEquals(0.9f, viewModel.state.value.interfaceSettings.interfaceScale)
        assertEquals(1.15f, viewModel.state.value.interfaceSettings.codeScale)
    }

    @Test
    fun `keyboard mapped events retain public ViewModel guards`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val chat = NoOpChatActions()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = chat, mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        KeyboardAction.SEND.toChatUiEvent()?.let(viewModel::onEvent)
        KeyboardAction.STOP_TURN.toChatUiEvent()?.let(viewModel::onEvent)
        advanceUntilIdle()
        assertTrue(chat.startTurnCalls.isEmpty())
        assertTrue(chat.interruptedTurns.isEmpty())

        KeyboardAction.OPEN_NAVIGATION.toChatUiEvent()?.let(viewModel::onEvent)
        advanceUntilIdle()
        assertEquals(ChatPickerUi.NAVIGATION, viewModel.state.value.activePicker)
        KeyboardAction.NEW_TASK.toChatUiEvent()?.let(viewModel::onEvent)
        advanceUntilIdle()
        assertEquals(null, repository.focusedThreadId.value)
    }

    @Test
    fun `dynamic shortcuts publish recent server threads and validate routes before navigation`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val actions = RecordingThreadActions(repository)
        val publisher = RecordingShortcutPublisher()
        val inbox = FakeShortcutRouteInbox()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = actions, chat = NoOpChatActions(), mappingDispatcher = dispatcher,
            shortcutWorkflow = DefaultShortcutWorkflow(
                DefaultShortcutCoordinator(
                    FakeViewModelEnvironmentRepository(), repository, EnvironmentSelector {}, actions,
                    publisher, inbox, CoroutineScope(dispatcher),
                ),
            ),
        )
        runCurrent()
        advanceUntilIdle()

        assertEquals("new-task:environment", publisher.latest.first().id)
        assertEquals(ShortcutRoute.NewTask("environment", "project-1"), publisher.latest.first().route)
        assertTrue(publisher.latest.size <= 4)
        assertEquals(listOf("thread-2", "thread-1"), publisher.latest.drop(1).map { (it.route as ShortcutRoute.Thread).threadId })
        inbox.receive(ShortcutParseResult.Valid(ShortcutRoute.Thread("environment", "project-1", "thread-1")))
        advanceUntilIdle()
        assertEquals("thread-1", actions.selectedThreads.last())
        inbox.receive(ShortcutParseResult.Valid(ShortcutRoute.NewTask("missing-environment", "project-1")))
        advanceUntilIdle()
        assertEquals("This shortcut's environment is no longer registered.", viewModel.state.value.shortcutError)
        inbox.receive(ShortcutParseResult.Valid(ShortcutRoute.NewTask("environment", "missing-project")))
        advanceUntilIdle()
        assertEquals("Choose an available project for this new task.", viewModel.state.value.shortcutError)

        inbox.receive(ShortcutParseResult.Valid(ShortcutRoute.Thread("environment", "project-1", "missing")))
        advanceUntilIdle()
        assertEquals("This shortcut's thread or project is no longer available.", viewModel.state.value.shortcutError)
        assertEquals("thread-1", actions.selectedThreads.last())
        inbox.receive(ShortcutParseResult.Invalid("Shortcut route is malformed."))
        advanceUntilIdle()
        assertEquals("Shortcut route is malformed.", viewModel.state.value.shortcutError)
    }

    @Test
    fun `shortcut coordinator publishes and routes without a chat view model`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val actions = RecordingThreadActions(repository)
        val publisher = RecordingShortcutPublisher()
        val inbox = FakeShortcutRouteInbox()

        DefaultShortcutCoordinator(
            FakeViewModelEnvironmentRepository(),
            repository,
            EnvironmentSelector {},
            actions,
            publisher,
            inbox,
            backgroundScope,
        )
        runCurrent()

        assertEquals("new-task:environment", publisher.latest.first().id)
        inbox.receive(ShortcutParseResult.Valid(ShortcutRoute.Thread("environment", "project-1", "thread-1")))
        runCurrent()
        assertEquals("thread-1", actions.selectedThreads.last())
    }

    @Test
    fun `shared text requires selection creates only a new task draft and survives retry`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val imports = FakeSharedTextImportRepository(PendingSharedText("fingerprint", "Review https://example.test"))
        val drafts = FakeComposerDraftRepository()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
            composerDrafts = drafts,
            sharedTextWorkflow = DefaultSharedTextWorkflow(
                imports, FakeViewModelEnvironmentRepository(), EnvironmentSelector {}, repository,
                RecordingThreadActions(repository), drafts,
            ),
        )
        advanceUntilIdle()
        assertEquals("Review https://example.test", viewModel.state.value.sharedTextImport?.text)
        viewModel.onEvent(ChatUiEvent.SharedImportConfirmed)
        advanceUntilIdle()
        assertTrue(drafts.writes.isEmpty())

        viewModel.onEvent(ChatUiEvent.SharedImportEnvironmentSelected("environment"))
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.SharedImportProjectSelected("project-1"))
        imports.importFailure = IllegalStateException("Receipt update failed")
        viewModel.onEvent(ChatUiEvent.SharedImportConfirmed)
        advanceUntilIdle()
        assertEquals("Receipt update failed", viewModel.state.value.sharedTextImport?.errorMessage)
        assertEquals("new-project:project-1", drafts.writes.single().second)
        assertEquals(null, repository.focusedThreadId.value)

        imports.importFailure = null
        viewModel.onEvent(ChatUiEvent.SharedImportConfirmed)
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.sharedTextImport)
        assertEquals(1, drafts.writes.size)
        assertEquals("Review https://example.test", drafts.writes.last().third)
        assertTrue(NoOpChatActions().startTurnCalls.isEmpty())
    }

    @Test
    fun `shared text discard removes durable review without draft or dispatch`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val imports = FakeSharedTextImportRepository(PendingSharedText("fingerprint", "discard me"))
        val drafts = FakeComposerDraftRepository()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
            composerDrafts = drafts,
            sharedTextWorkflow = DefaultSharedTextWorkflow(
                imports, FakeViewModelEnvironmentRepository(), EnvironmentSelector {}, repository,
                RecordingThreadActions(repository), drafts,
            ),
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.SharedImportDiscarded)
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.sharedTextImport)
        assertTrue(drafts.writes.isEmpty())
        assertEquals(listOf("fingerprint"), imports.discarded)
    }

    @Test
    fun `upstream deletion needs no custom capability and rejects a different environment`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val actions = RecordingThreadActions(repository)
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = actions, chat = NoOpChatActions(), mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.DeleteThreadRequested)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.canDeleteThread)
        assertTrue(viewModel.state.value.threadDeletion != null)
        viewModel.onEvent(ChatUiEvent.DeleteThreadDismissed)

        val config = requireNotNull(repository.clientConfig.value.value)
        repository.clientConfig.value = repository.clientConfig.value.copy(
            value = config.copy(
                environment = config.environment.copy(
                    environmentId = "wrong-environment",
                    capabilities = config.environment.capabilities.copy(),
                ),
            ),
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.DeleteThreadRequested)
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.threadDeletion)
        assertTrue(actions.deletedThreads.isEmpty())
    }

    @Test
    fun `permanent deletion guards pending work confirms retries and waits for disappearance`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val config = requireNotNull(repository.clientConfig.value.value)
        repository.clientConfig.value = repository.clientConfig.value.copy(
            value = config.copy(environment = config.environment.copy(capabilities = config.environment.capabilities.copy())),
        )
        val actions = RecordingThreadActions(repository)
        val outbox = FakeCommandOutbox()
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = actions, chat = NoOpChatActions(), mappingDispatcher = dispatcher, commandOutbox = outbox,
        )
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.DraftChanged("unsent work"))
        viewModel.onEvent(ChatUiEvent.DeleteThreadRequested)
        advanceUntilIdle()
        assertEquals(listOf("Unsent draft"), viewModel.state.value.threadDeletion?.blockers)
        viewModel.onEvent(ChatUiEvent.DeleteThreadDismissed)
        assertTrue(actions.deletedThreads.isEmpty())

        viewModel.onEvent(ChatUiEvent.DraftChanged(""))
        val current = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(
                thread = current.thread.copy(
                    session = ThreadSession("thread-1", "running", activeTurnId = "turn-1", updatedAt = "2026-08-06T00:00:00Z"),
                ),
            ),
        )
        viewModel.onEvent(ChatUiEvent.DeleteThreadRequested)
        advanceUntilIdle()
        assertEquals(listOf("Active turn"), viewModel.state.value.threadDeletion?.blockers)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = repository.focusedThread.value.value?.copy(
                thread = requireNotNull(repository.focusedThread.value.value).thread.copy(session = null),
            ),
        )
        outbox.entries += OutboxCommand("environment", ClientOrchestrationCommand.ArchiveThread("pending", "thread-1"), OutboxCommandStatus.PENDING, 0, null)
        viewModel.onEvent(ChatUiEvent.DeleteThreadRequested)
        advanceUntilIdle()
        assertEquals(listOf("Pending command"), viewModel.state.value.threadDeletion?.blockers)
        outbox.entries.clear()
        viewModel.onEvent(ChatUiEvent.DeleteThreadRequested)
        advanceUntilIdle()
        assertTrue(requireNotNull(viewModel.state.value.threadDeletion).canConfirm)

        actions.deleteFailure = IllegalStateException("Delete rejected")
        viewModel.onEvent(ChatUiEvent.DeleteThreadConfirmed)
        advanceUntilIdle()
        assertEquals("Delete rejected", viewModel.state.value.threadDeletion?.errorMessage)
        assertEquals("thread-1", viewModel.state.value.threadId)
        actions.deleteFailure = null
        viewModel.onEvent(ChatUiEvent.DeleteThreadConfirmed)
        viewModel.onEvent(ChatUiEvent.DeleteThreadConfirmed)
        advanceUntilIdle()
        assertEquals(listOf("thread-1", "thread-1"), actions.deletedThreads)
        assertTrue(requireNotNull(viewModel.state.value.threadDeletion).deleting)

        repository.focusedThread.value = ProjectionState(sequence = 9, source = ProjectionSource.LIVE, synchronized = true)
        repository.shell.value = repository.shell.value.copy(
            value = repository.shell.value.value?.copy(threads = repository.shell.value.value!!.threads.filterNot { it.id == "thread-1" }),
        )
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.threadDeletion)
        assertEquals("thread-2", repository.focusedThreadId.value)
    }

    @Test
    fun `supported thread snoozes retries wake and accepts automatic projected wake`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val config = requireNotNull(repository.clientConfig.value.value)
        repository.clientConfig.value = repository.clientConfig.value.copy(
            value = config.copy(
                environment = config.environment.copy(
                    capabilities = config.environment.capabilities.copy(threadSnooze = true),
                ),
            ),
        )
        val actions = RecordingThreadActions(repository)
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = actions,
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.threadSnooze.supported)
        assertTrue(viewModel.state.value.threadSnooze.canSnooze)
        viewModel.onEvent(ChatUiEvent.ThreadSnoozeRequested("2026-08-07T09:00:00Z"))
        viewModel.onEvent(ChatUiEvent.ThreadSnoozeRequested("2026-08-07T09:00:00Z"))
        advanceUntilIdle()
        assertEquals(listOf("thread-1" to "2026-08-07T09:00:00Z"), actions.snoozedThreads)
        assertTrue(viewModel.state.value.threadSnooze.actionInProgress)

        val current = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(
                thread = current.thread.copy(
                    snoozedAt = "2026-08-06T09:00:00Z",
                    snoozedUntil = "2026-08-07T09:00:00Z",
                ),
            ),
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value.threadSnooze.isSnoozed)
        assertEquals("2026-08-07T09:00:00Z", viewModel.state.value.threadSnooze.snoozedUntil)
        assertTrue(viewModel.state.value.threadSnooze.canWake)

        actions.wakeFailure = IllegalStateException("Wake rejected")
        viewModel.onEvent(ChatUiEvent.ThreadWakeRequested)
        advanceUntilIdle()
        assertEquals("Wake rejected", viewModel.state.value.threadSnooze.errorMessage)
        assertTrue(viewModel.state.value.threadSnooze.canWake)
        actions.wakeFailure = null
        viewModel.onEvent(ChatUiEvent.ThreadWakeRequested)
        viewModel.onEvent(ChatUiEvent.ThreadWakeRequested)
        advanceUntilIdle()
        assertEquals(listOf("thread-1", "thread-1"), actions.wokenThreads)

        val snoozed = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = snoozed.copy(
                thread = snoozed.thread.copy(
                    snoozedAt = null,
                    snoozedUntil = null,
                    lastWakeReason = "activity",
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.threadSnooze.isSnoozed)
        assertEquals("activity", viewModel.state.value.threadSnooze.wakeReason)
        assertEquals(false, viewModel.state.value.threadSnooze.actionInProgress)
    }

    @Test
    fun `unsupported or mismatched environment never exposes snooze commands`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val actions = RecordingThreadActions(repository)
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(),
            repository = repository,
            connection = FakeConnectionController(),
            environmentService = EnvironmentSelector {},
            threads = actions,
            chat = NoOpChatActions(),
            mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.threadSnooze.supported)
        viewModel.onEvent(ChatUiEvent.ThreadSnoozeRequested("2026-08-07T09:00:00Z"))
        viewModel.onEvent(ChatUiEvent.ThreadWakeRequested)
        advanceUntilIdle()
        assertTrue(actions.snoozedThreads.isEmpty())
        assertTrue(actions.wokenThreads.isEmpty())

        val config = requireNotNull(repository.clientConfig.value.value)
        repository.clientConfig.value = repository.clientConfig.value.copy(
            value = config.copy(
                environment = config.environment.copy(
                    environmentId = "other-environment",
                    capabilities = config.environment.capabilities.copy(threadSnooze = true),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.threadSnooze.supported)

        repository.clientConfig.value = repository.clientConfig.value.copy(
            value = config.copy(
                environment = config.environment.copy(
                    capabilities = config.environment.capabilities.copy(threadSnooze = true),
                ),
            ),
        )
        val current = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(thread = current.thread.copy(id = "stale-thread")),
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.ThreadSnoozeRequested("2026-08-07T09:00:00Z"))
        advanceUntilIdle()
        assertTrue(actions.snoozedThreads.isEmpty())
    }

    @Test
    fun `session termination fails retryably then waits for projection independently of turn stop`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val detail = requireNotNull(repository.focusedThread.value.value).thread
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = repository.focusedThread.value.value?.copy(
                thread = detail.copy(
                    latestTurn = LatestTurn(
                        turnId = "turn-1",
                        state = "running",
                        requestedAt = "2026-08-06T09:59:00Z",
                        startedAt = "2026-08-06T10:00:00Z",
                        completedAt = null,
                    ),
                    session = ThreadSession(
                        threadId = "thread-1",
                        status = "running",
                        activeTurnId = "turn-1",
                        updatedAt = "2026-08-06T10:00:00Z",
                    ),
                ),
            ),
        )
        val chat = NoOpChatActions(terminateSessionFailure = IllegalStateException("Stop rejected"))
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

        assertTrue(viewModel.state.value.isTurnRunning)
        assertEquals(
            ChatTurnTimingUi(
                startedAt = "2026-08-06T10:00:00Z",
                running = true,
            ),
            viewModel.state.value.latestTurnTiming,
        )
        assertTrue(viewModel.state.value.sessionTermination.canTerminate)
        viewModel.onEvent(ChatUiEvent.SessionTerminationRequested)
        viewModel.onEvent(ChatUiEvent.SessionTerminationRequested)
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), chat.terminatedSessions)
        assertEquals("Stop rejected", viewModel.state.value.sessionTermination.errorMessage)
        assertTrue(viewModel.state.value.sessionTermination.canTerminate)

        chat.terminateSessionFailure = null
        viewModel.onEvent(ChatUiEvent.SessionTerminationRequested)
        viewModel.onEvent(ChatUiEvent.SessionTerminationRequested)
        viewModel.onEvent(ChatUiEvent.TurnInterruptRequested)
        advanceUntilIdle()

        assertEquals(listOf("thread-1", "thread-1"), chat.terminatedSessions)
        assertEquals(listOf("thread-1" to "turn-1"), chat.interruptedTurns)
        assertTrue(viewModel.state.value.sessionTermination.terminating)
        assertEquals(false, viewModel.state.value.sessionTermination.terminal)

        val running = requireNotNull(repository.focusedThread.value.value).thread
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = repository.focusedThread.value.value?.copy(
                thread = running.copy(
                    session = running.session?.copy(
                        status = "stopped",
                        activeTurnId = null,
                        updatedAt = "2026-08-06T10:01:00Z",
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.sessionTermination.terminating)
        assertTrue(viewModel.state.value.sessionTermination.terminal)
        assertEquals(false, viewModel.state.value.isTurnRunning)
        viewModel.onEvent(ChatUiEvent.SessionTerminationRequested)
        advanceUntilIdle()
        assertEquals(2, chat.terminatedSessions.size)
    }

    @Test
    fun `completed turn timing does not remain running when the session is idle`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val current = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(
                thread = current.thread.copy(
                    latestTurn = LatestTurn(
                        turnId = "turn-completed",
                        state = "running",
                        requestedAt = "2026-08-29T10:00:00Z",
                        startedAt = "2026-08-29T10:00:05Z",
                        completedAt = "2026-08-29T10:01:00Z",
                    ),
                    session = ThreadSession(
                        threadId = "thread-1",
                        status = "idle",
                        activeTurnId = null,
                        updatedAt = "2026-08-29T10:01:00Z",
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

        assertEquals(false, viewModel.state.value.isTurnRunning)
        assertEquals(
            ChatTurnTimingUi(
                startedAt = "2026-08-29T10:00:05Z",
                completedAt = "2026-08-29T10:01:00Z",
                running = false,
            ),
            viewModel.state.value.latestTurnTiming,
        )
    }

    @Test
    fun `latest proposed plan continues once and exposes failure retry truthfully`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val detail = requireNotNull(repository.focusedThread.value.value).thread
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = repository.focusedThread.value.value?.copy(
                thread = detail.copy(
                    proposedPlans = listOf(
                        proposedPlan("older-plan", "Older"),
                        proposedPlan("latest-plan", "Latest provider-neutral plan", updatedAt = "2026-08-06T02:00:00Z"),
                    ),
                ),
            ),
        )
        val chat = NoOpChatActions(continuePlanFailure = IllegalStateException("Continuation rejected"))
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

        viewModel.onEvent(ChatUiEvent.ProposedPlanContinueRequested("older-plan"))
        viewModel.onEvent(ChatUiEvent.ProposedPlanContinueRequested("latest-plan"))
        viewModel.onEvent(ChatUiEvent.ProposedPlanContinueRequested("latest-plan"))
        advanceUntilIdle()

        assertEquals(listOf("thread-1" to "latest-plan"), chat.planContinuations)
        val card = viewModel.state.value.timeline
            .filterIsInstance<ChatTimelineItemUi.Activity>()
            .map { it.value }
            .filterIsInstance<ChatActivityUi.ProposedPlan>()
            .single { it.id == "latest-plan" }
        assertEquals("Latest provider-neutral plan", card.planMarkdown)
        assertEquals("Continuation rejected", card.errorMessage)
        assertEquals(true, card.canContinue)
        val older = viewModel.state.value.timeline
            .filterIsInstance<ChatTimelineItemUi.Activity>()
            .map { it.value }
            .filterIsInstance<ChatActivityUi.ProposedPlan>()
            .single { it.id == "older-plan" }
        assertEquals(false, older.canContinue)

        chat.continuePlanFailure = null
        viewModel.onEvent(ChatUiEvent.ProposedPlanContinueRequested("latest-plan"))
        viewModel.onEvent(ChatUiEvent.ProposedPlanContinueRequested("latest-plan"))
        advanceUntilIdle()
        assertEquals(2, chat.planContinuations.size)
        val pending = viewModel.state.value.timeline
            .filterIsInstance<ChatTimelineItemUi.Activity>()
            .map { it.value }
            .filterIsInstance<ChatActivityUi.ProposedPlan>()
            .single { it.id == "latest-plan" }
        assertEquals(true, pending.continuing)
        assertEquals(false, pending.canContinue)
    }

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
    fun `project removal cancellation never dispatches and confirmation selects server-confirmed fallback`() =
        runTest(dispatcher) {
            val repository = FakeOrchestrationRepository()
            repository.shell.value = ProjectionState(
                value = pickerShell(),
                sequence = 1,
                source = ProjectionSource.LIVE,
                synchronized = true,
            )
            val actions = RecordingProjectActions()
            val threads = RecordingThreadActions(repository)
            val viewModel = ChatViewModel(
                environments = FakeViewModelEnvironmentRepository(),
                repository = repository,
                connection = FakeConnectionController(),
                environmentService = EnvironmentSelector {},
                threads = threads,
                chat = NoOpChatActions(),
                mappingDispatcher = dispatcher,
                projectWorkflow = DefaultProjectWorkflow(repository, actions, threads),
            )
            advanceUntilIdle()

            viewModel.onEvent(ChatUiEvent.ProjectRemovalRequested("project-1"))
            viewModel.onEvent(ChatUiEvent.ProjectRemovalDismissed)
            advanceUntilIdle()
            assertEquals(emptyList<String>(), actions.removed)
            assertEquals(null, viewModel.state.value.projectRemoval)

            viewModel.onEvent(ChatUiEvent.ProjectRemovalRequested("project-1"))
            viewModel.onEvent(ChatUiEvent.ProjectRemovalConfirmed)
            advanceUntilIdle()
            assertEquals(listOf("project-1"), actions.removed)
            assertEquals("project-2", threads.selectedProjects.last())
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
        assertEquals(listOf(1, 1), viewModel.state.value.threadPicker.projects.map { it.unsettledThreadCount })
        assertEquals(
            listOf("Thread One", "Settled Thread"),
            viewModel.state.value.threadPicker.projects.first().searchableThreadTitles,
        )
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

        assertEquals(listOf("project-2"), threadActions.selectedProjects)
        assertEquals(listOf("thread-2", "thread-1"), threadActions.selectedThreads)
        assertEquals("project-1", viewModel.state.value.threadPicker.selectedProjectId)
        assertEquals("thread-1", viewModel.state.value.threadId)

        viewModel.onEvent(ChatUiEvent.ProjectThreadsRequested("project-2"))
        advanceUntilIdle()

        assertEquals(ChatPickerUi.PROJECT_THREAD, viewModel.state.value.activePicker)
        assertEquals("project-2", viewModel.state.value.threadPicker.selectedProjectId)
        assertEquals(listOf("thread-2"), viewModel.state.value.threadPicker.threads.map { it.id })

        viewModel.onEvent(ChatUiEvent.PickerDismissed)
        viewModel.onEvent(ChatUiEvent.ProjectPaneExpanded("project-1"))
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.activePicker)
        assertEquals("project-1", viewModel.state.value.threadPicker.selectedProjectId)
        assertEquals(listOf("thread-1"), viewModel.state.value.threadPicker.threads.map { it.id })
    }

    @Test
    fun `settling the focused thread falls back to the latest other open thread`() = runTest(dispatcher) {
        val shell = pickerShell().let {
            it.copy(
                threads = it.threads + listOf(
                    OrchestrationThreadShell(
                        id = "thread-older",
                        projectId = "project-1",
                        title = "Older",
                        createdAt = "2026-07-29T09:00:00Z",
                        updatedAt = "2026-07-29T09:00:00Z",
                    ),
                    OrchestrationThreadShell(
                        id = "thread-newer",
                        projectId = "project-1",
                        title = "Newer",
                        createdAt = "2026-07-29T09:00:00Z",
                        updatedAt = "2026-07-29T09:30:00Z",
                    ),
                ),
            )
        }
        val repository = FakeOrchestrationRepository().apply {
            this.shell.value = ProjectionState(value = shell, sequence = 7, source = ProjectionSource.LIVE, synchronized = true)
            selectedProjectId.value = "project-1"
            focusedThreadId.value = "thread-1"
        }
        val threadActions = RecordingThreadActions(repository)
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = threadActions, chat = NoOpChatActions(), mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROJECT_THREAD))
        viewModel.onEvent(ChatUiEvent.ThreadSettleRequested("thread-1"))
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), threadActions.settledThreads)
        assertEquals(listOf<String?>("thread-newer"), threadActions.selectedThreads)
        assertEquals("thread-newer", viewModel.state.value.threadId)
        assertEquals(ChatPickerUi.PROJECT_THREAD, viewModel.state.value.activePicker)
    }

    @Test
    fun `settling the only open thread shows a new thread and closes the panel`() = runTest(dispatcher) {
        val repository = FakeOrchestrationRepository().apply {
            shell.value = ProjectionState(value = pickerShell(), sequence = 7, source = ProjectionSource.LIVE, synchronized = true)
            selectedProjectId.value = "project-1"
            focusedThreadId.value = "thread-1"
        }
        val threadActions = RecordingThreadActions(repository)
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = threadActions, chat = NoOpChatActions(), mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROJECT_THREAD))
        viewModel.onEvent(ChatUiEvent.ThreadSettleRequested("thread-1"))
        advanceUntilIdle()

        assertEquals(listOf("thread-1"), threadActions.settledThreads)
        assertEquals(listOf<String?>(null), threadActions.selectedThreads)
        assertEquals(null, viewModel.state.value.threadId)
        assertEquals("project-1", repository.selectedProjectId.value)
        assertEquals(null, viewModel.state.value.activePicker)
    }

    @Test
    fun `settling another thread keeps the focused one and switching projects closes the panel`() = runTest(dispatcher) {
        val repository = FakeOrchestrationRepository().apply {
            shell.value = ProjectionState(value = pickerShell(), sequence = 7, source = ProjectionSource.LIVE, synchronized = true)
            selectedProjectId.value = "project-1"
            focusedThreadId.value = "thread-1"
        }
        val threadActions = RecordingThreadActions(repository)
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = threadActions, chat = NoOpChatActions(), mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROJECT_THREAD))
        viewModel.onEvent(ChatUiEvent.ThreadSettleRequested("thread-2"))
        advanceUntilIdle()
        assertTrue(threadActions.selectedThreads.isEmpty())
        assertEquals("thread-1", viewModel.state.value.threadId)
        assertEquals(ChatPickerUi.PROJECT_THREAD, viewModel.state.value.activePicker)

        viewModel.onEvent(ChatUiEvent.ProjectQuickSwitchRequested("project-2"))
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.activePicker)
        assertEquals("thread-2", viewModel.state.value.threadId)
    }

    @Test
    fun `background thread updates do not rebuild the focused timeline`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val detail = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = detail.copy(
                thread = detail.thread.copy(
                    messages = listOf(
                        OrchestrationMessage(
                            id = "message-1",
                            role = "assistant",
                            text = "Existing response",
                            createdAt = "2026-07-29T10:01:00Z",
                            updatedAt = "2026-07-29T10:01:00Z",
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
        val focusedTimeline = viewModel.state.value.timeline
        val shell = requireNotNull(repository.shell.value.value)

        repository.shell.value = repository.shell.value.copy(
            value = shell.copy(
                threads = shell.threads.map { thread ->
                    if (thread.id == "thread-2") {
                        thread.copy(
                            session = ThreadSession(
                                threadId = thread.id,
                                status = "running",
                                updatedAt = "2026-07-29T10:03:00Z",
                            ),
                            updatedAt = "2026-07-29T10:03:00Z",
                        )
                    } else {
                        thread
                    }
                },
            ),
            sequence = 8,
        )
        advanceUntilIdle()

        assertSame(focusedTimeline, viewModel.state.value.timeline)
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
    fun `started threads switch models within their provider and keep the choice to themselves`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model")).apply {
            val config = requireNotNull(clientConfig.value.value)
            val provider = config.providers.single()
            clientConfig.value = clientConfig.value.copy(
                value = config.copy(
                    providers = listOf(
                        provider.copy(driver = "codex", models = provider.models + ProviderModel("model-2", "Model 2")),
                        ProviderInstance("other", displayName = "Other", driver = "claudeAgent", models = listOf(ProviderModel("opus", "Opus"))),
                    ),
                ),
            )
            val detail = requireNotNull(focusedThread.value.value)
            focusedThread.value = focusedThread.value.copy(
                value = detail.copy(
                    thread = detail.thread.copy(
                        session = ThreadSession("thread-1", "ready", "codex", "provider", updatedAt = "now"),
                    ),
                ),
            )
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

        assertEquals(listOf("provider/model", "provider/model-2"), viewModel.state.value.composer.providerModels.map { it.id })
        assertEquals(listOf("codex", "codex"), viewModel.state.value.composer.providerModels.map { it.providerDriver })
        assertEquals("Provider", viewModel.state.value.composer.modelLockLabel)

        viewModel.onEvent(ChatUiEvent.ProviderModelSelected("provider/model-2"))
        viewModel.onEvent(ChatUiEvent.ProviderModelSelected("other/opus"))
        viewModel.onEvent(ChatUiEvent.DraftChanged("use the other model"))
        viewModel.onEvent(ChatUiEvent.MessageSubmitted)
        advanceUntilIdle()

        assertEquals("provider/model-2", viewModel.state.value.composer.selectedProviderModelId)
        assertEquals(ModelSelection("provider", "model-2"), chat.startTurnCalls.single().modelSelection.copy(options = null))

        viewModel.onEvent(ChatUiEvent.ThreadSelected("thread-2"))
        advanceUntilIdle()

        assertNull(viewModel.state.value.composer.modelLockLabel)
        assertEquals(3, viewModel.state.value.composer.providerModels.size)
        assertEquals("provider/model", viewModel.state.value.composer.selectedProviderModelId)
    }

    @Test
    fun `message submission preserves inherited model options and runtime mode`() = runTest(dispatcher) {
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
        assertEquals("full-access", call.runtimeMode)
        assertEquals("", viewModel.draft.value)
        assertEquals(false, viewModel.state.value.composer.sending)
        assertEquals(null, viewModel.state.value.composer.errorMessage)
    }

    @Test
    fun `selected image is previewed submitted and retained when reading another image fails`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val chat = NoOpChatActions()
        val image = de.chennemann.agentic.t3.contract.UploadChatAttachment(
            name = "one.png",
            mimeType = "image/png",
            sizeBytes = 3,
            dataUrl = "data:image/png;base64,AQID",
        )
        val reader = ImageAttachmentReader { uri ->
            if (uri == "bad") error("Unreadable image") else image
        }
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = chat, mappingDispatcher = dispatcher,
            imageAttachments = reader,
        )
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.ImagesSelected(listOf("good")))
        advanceUntilIdle()
        assertEquals("one.png", viewModel.state.value.composer.imageAttachments.single().name)
        viewModel.onEvent(ChatUiEvent.ImagesSelected(listOf("bad")))
        advanceUntilIdle()
        assertEquals("Unreadable image", viewModel.state.value.composer.errorMessage)
        assertEquals(1, viewModel.state.value.composer.imageAttachments.size)

        val draftId = viewModel.state.value.composer.imageAttachments.single().id
        viewModel.onEvent(ChatUiEvent.MessageSubmitted)
        advanceUntilIdle()
        assertEquals(listOf(image.copy(id = draftId)), chat.startTurnCalls.single().attachments)
        assertNull(chat.startTurnCalls.single().context)
        assertTrue(viewModel.state.value.composer.imageAttachments.isEmpty())
    }

    @Test
    fun `annotated image is rendered and sent with comments and context records`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val chat = NoOpChatActions()
        val image = de.chennemann.agentic.t3.contract.UploadChatAttachment(
            name = "shot.png",
            mimeType = "image/png",
            sizeBytes = 3,
            dataUrl = "data:image/png;base64,AQID",
        )
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = chat, mappingDispatcher = dispatcher,
            imageAttachments = { image },
            annotatedImages = { source, marks, offset ->
                source.copy(name = "shot-annotated.png", dataUrl = "data:image/png;base64,${marks.size}$offset")
            },
        )
        advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.ImagesSelected(listOf("good")))
        advanceUntilIdle()
        val draftId = viewModel.state.value.composer.imageAttachments.single().id
        val mark = de.chennemann.agentic.domain.attachments.ImageAnnotation("m1", 0.1f, 0.1f, 0.2f, 0.2f, "Make this bigger")
        viewModel.onEvent(ChatUiEvent.ImageAnnotationsSaved(draftId, listOf(mark)))
        advanceUntilIdle()
        assertEquals(listOf(mark), viewModel.state.value.composer.imageAttachments.single().annotations)

        viewModel.onEvent(ChatUiEvent.DraftChanged("Please fix"))
        viewModel.onEvent(ChatUiEvent.MessageSubmitted)
        advanceUntilIdle()

        val call = chat.startTurnCalls.single()
        assertEquals("Please fix\n\nannotations for shot.png:\n1. Make this bigger", call.prompt)
        assertEquals("shot-annotated.png", call.attachments.single().name)
        assertEquals("data:image/png;base64,10", call.attachments.single().dataUrl)
        assertEquals(draftId, call.attachments.single().id)
        val record = call.context!!["records"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(draftId), record["attachmentId"])
        assertEquals(JsonPrimitive("image"), record["kind"])
    }

    @Test
    fun `annotating a conversation image attaches it to the reply with prior marks read-only`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val viewModel = ChatViewModel(
            environments = FakeViewModelEnvironmentRepository(), repository = repository,
            connection = FakeConnectionController(), environmentService = EnvironmentSelector {},
            threads = RecordingThreadActions(repository), chat = NoOpChatActions(), mappingDispatcher = dispatcher,
        )
        advanceUntilIdle()
        val prior = de.chennemann.agentic.domain.attachments.ImageAnnotation("p", 0f, 0f, 0.1f, 0.1f, "old")
        val added = de.chennemann.agentic.domain.attachments.ImageAnnotation("n", 0.5f, 0.5f, 0.1f, 0.1f, "new")
        viewModel.onEvent(ChatUiEvent.ConversationImageAnnotated("data:image/jpeg;base64,AQID", "sent.jpg", listOf(prior, added), 1))
        advanceUntilIdle()
        val draft = viewModel.state.value.composer.imageAttachments.single()
        assertEquals("sent.jpg", draft.name)
        assertEquals("image/jpeg", draft.mimeType)
        assertEquals(listOf(prior, added), draft.annotations)
        assertEquals(1, draft.burnedAnnotationCount)
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
                            payload = T3Json.parseToJsonElement(
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
                                          "value": "replace",
                                          "label": "Replace it"
                                        },
                                        {"label": "Decide later", "description": ""}
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
                UserInputOptionUi(id = "Decide later", label = "Decide later", description = ""),
            ),
            question.options,
        )
        assertFalse(input.request.canSubmit)
        viewModel.onEvent(ChatUiEvent.UserInputOptionToggled("request-1", "approach", "Keep current behavior"))
        viewModel.onEvent(ChatUiEvent.UserInputOptionToggled("request-1", "approach", "replace"))
        advanceUntilIdle()
        fun pending() = (viewModel.state.value.timeline.single() as ChatTimelineItemUi.Activity).value as ChatActivityUi.UserInput
        assertEquals(setOf("replace"), pending().request.questions.single().selectedOptionIds)
        viewModel.onEvent(ChatUiEvent.UserInputTextChanged("request-1", "approach", "Custom direction"))
        advanceUntilIdle()
        assertTrue(pending().request.questions.single().selectedOptionIds.isEmpty())
        chat.userInputFailure = IllegalStateException("Retry this response")
        viewModel.onEvent(ChatUiEvent.UserInputSubmitted("request-1"))
        advanceUntilIdle()
        assertFalse(pending().request.responseInFlight)
        assertEquals("Custom direction", pending().request.questions.single().textAnswer)
        chat.userInputFailure = null
        viewModel.onEvent(ChatUiEvent.UserInputSubmitted("request-1"))
        advanceUntilIdle()
        assertEquals(JsonPrimitive("Custom direction"), chat.userInputResponses.single()["approach"])
        assertTrue(pending().request.responseInFlight)
        assertNull(viewModel.state.value.composer.errorMessage)
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
    fun `started tool call is visible until completion replaces it`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val current = requireNotNull(repository.focusedThread.value.value)
        val started = toolActivity(
            id = "tool-started",
            kind = "tool.started",
            status = "inProgress",
            callId = "call-active",
            command = "./gradlew build",
        )
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(thread = current.thread.copy(activities = listOf(started))),
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

        val runningGroup = viewModel.state.value.timeline.single() as ChatTimelineItemUi.ToolGroup
        assertEquals(ActivityStatusUi.RUNNING, runningGroup.activities.single().status)

        val completed = toolActivity(
            id = "tool-completed",
            kind = "tool.completed",
            status = "completed",
            callId = "call-active",
            command = "./gradlew build",
        )
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(thread = current.thread.copy(activities = listOf(started, completed))),
            sequence = 8,
        )
        advanceUntilIdle()

        val completedGroup = viewModel.state.value.timeline.single() as ChatTimelineItemUi.ToolGroup
        assertEquals(1, completedGroup.activities.size)
        assertEquals(ActivityStatusUi.COMPLETED, completedGroup.activities.single().status)
    }

    @Test
    fun `started tool call is not running after its turn completes`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val current = requireNotNull(repository.focusedThread.value.value)
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(
                thread = current.thread.copy(
                    latestTurn = LatestTurn(
                        turnId = "turn-complete",
                        state = "completed",
                        requestedAt = "2026-07-29T10:00:00Z",
                        completedAt = "2026-07-29T10:01:00Z",
                    ),
                    activities = listOf(
                        toolActivity(
                            id = "tool-started",
                            kind = "tool.started",
                            status = "inProgress",
                            callId = "call-active",
                            command = "./gradlew build",
                            turnId = "turn-complete",
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

        val summary = viewModel.state.value.timeline.single() as ChatTimelineItemUi.WorkSummary
        val group = summary.items.single() as ChatTimelineItemUi.ToolGroup
        assertEquals(ActivityStatusUi.STOPPED, group.activities.single().status)
        assertEquals(
            ChatTurnTimingUi(
                startedAt = "2026-07-29T10:00:00Z",
                completedAt = "2026-07-29T10:01:00Z",
            ),
            viewModel.state.value.latestTurnTiming,
        )
    }

    @Test
    fun `tool calls group between the assistant messages that surround them`() = runTest(dispatcher) {
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
                            text = "First explanation",
                            createdAt = "2026-07-29T10:01:00Z",
                        ),
                        orchestrationMessage(
                            id = "assistant-2",
                            turnId = "turn-1",
                            role = "assistant",
                            text = "Second explanation",
                            createdAt = "2026-07-29T10:03:00Z",
                        ),
                        orchestrationMessage(
                            id = "assistant-final",
                            turnId = "turn-1",
                            role = "assistant",
                            text = "Final answer",
                            createdAt = "2026-07-29T10:05:00Z",
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
                            createdAt = "2026-07-29T10:02:00Z",
                        ),
                        toolActivity(
                            id = "turn-1-tool-b",
                            kind = "tool.completed",
                            status = "completed",
                            callId = "call-1-b",
                            command = "second command",
                            turnId = "turn-1",
                            createdAt = "2026-07-29T10:02:30Z",
                        ),
                        toolActivity(
                            id = "turn-1-tool-c",
                            kind = "tool.completed",
                            status = "completed",
                            callId = "call-1-c",
                            command = "third command",
                            turnId = "turn-1",
                            createdAt = "2026-07-29T10:04:00Z",
                        ),
                        toolActivity(
                            id = "turn-1-tool-d",
                            kind = "tool.completed",
                            status = "completed",
                            callId = "call-1-d",
                            command = "fourth command",
                            turnId = "turn-1",
                            createdAt = "2026-07-29T10:04:30Z",
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
                "assistant-1",
                "turn-1",
                "assistant-final",
            ),
            viewModel.state.value.timeline.map { it.id },
        )
        val summary = viewModel.state.value.timeline[2] as ChatTimelineItemUi.WorkSummary
        assertEquals(2, (summary.items[0] as ChatTimelineItemUi.ToolGroup).activities.size)
        assertEquals("assistant-2", summary.items[1].id)
        assertEquals(2, (summary.items[2] as ChatTimelineItemUi.ToolGroup).activities.size)
        val messageCopyState = viewModel.state.value.timeline
            .filterIsInstance<ChatTimelineItemUi.Message>()
            .associate { it.value.id to it.value.canCopy }
        assertEquals(
            mapOf(
                "user-1" to false,
                "assistant-1" to false,
                "assistant-final" to true,
            ),
            messageCopyState,
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
    fun `new thread drafts persist per environment and project without following provider changes`() =
        runTest(dispatcher) {
            val drafts = FakeComposerDraftRepository()
            val chat = NoOpChatActions()
            val repository = submissionRepository(ModelSelection("provider", "model")).apply {
                focusedThreadId.value = null
                focusedThread.value = ProjectionState()
            }
            val threads = RecordingThreadActions(repository)
            val viewModel = ChatViewModel(
                environments = FakeViewModelEnvironmentRepository(),
                repository = repository,
                connection = FakeConnectionController(),
                environmentService = EnvironmentSelector {},
                threads = threads,
                chat = chat,
                mappingDispatcher = dispatcher,
                composerDrafts = drafts,
            )
            advanceUntilIdle()

            viewModel.onEvent(ChatUiEvent.DraftChanged("provider-neutral new project draft"))
            viewModel.onEvent(ChatUiEvent.ProviderModelSelected("provider/model"))
            advanceUntilIdle()
            assertEquals("provider-neutral new project draft", viewModel.draft.value)

            viewModel.onEvent(ChatUiEvent.ProjectSelected("project-2"))
            advanceUntilIdle()
            assertEquals("", viewModel.draft.value)
            viewModel.onEvent(ChatUiEvent.DraftChanged("second project draft"))
            viewModel.onEvent(ChatUiEvent.ProjectSelected("project-1"))
            advanceUntilIdle()
            assertEquals("provider-neutral new project draft", viewModel.draft.value)

            val recreatedRepository = submissionRepository(ModelSelection("provider", "model")).apply {
                focusedThreadId.value = null
                focusedThread.value = ProjectionState()
            }
            val recreatedChat = NoOpChatActions()
            val recreated = ChatViewModel(
                environments = FakeViewModelEnvironmentRepository(),
                repository = recreatedRepository,
                connection = FakeConnectionController(),
                environmentService = EnvironmentSelector {},
                threads = RecordingThreadActions(recreatedRepository),
                chat = recreatedChat,
                mappingDispatcher = dispatcher,
                composerDrafts = drafts,
            )
            advanceUntilIdle()
            assertEquals("provider-neutral new project draft", recreated.draft.value)
            assertEquals(emptyList<StartTurnCall>(), chat.startTurnCalls)
            assertEquals(emptyList<StartTurnCall>(), recreatedChat.startTurnCalls)
        }

    @Test
    fun `explicit discard clears only the current conversation draft`() = runTest(dispatcher) {
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
        viewModel.onEvent(ChatUiEvent.DraftChanged("discard me"))
        viewModel.onEvent(ChatUiEvent.DraftDiscardRequested)
        advanceUntilIdle()

        assertEquals("", viewModel.draft.value)
        assertEquals("", drafts.observe("environment", "thread-1").first())
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

    @Test
    fun `started tool call without provider id merges with identified completion`() = runTest(dispatcher) {
        val repository = submissionRepository(ModelSelection("provider", "model"))
        val current = requireNotNull(repository.focusedThread.value.value)
        val started = OrchestrationActivity(
            id = "tool-started",
            kind = "tool.started",
            tone = "tool",
            summary = "Run command started",
            payload = T3Json.parseToJsonElement(
                """
                {
                  "itemType": "command_execution",
                  "detail": "./gradlew build"
                }
                """.trimIndent(),
            ).jsonObject,
            createdAt = "2026-07-29T10:00:00Z",
        )
        val completed = toolActivity(
            id = "tool-completed",
            kind = "tool.completed",
            status = "completed",
            callId = "call-active",
            command = "./gradlew build",
        )
        repository.focusedThread.value = repository.focusedThread.value.copy(
            value = current.copy(
                thread = current.thread.copy(activities = listOf(started, completed)),
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
        assertEquals(1, group.activities.size)
        assertEquals(ActivityStatusUi.COMPLETED, group.activities.single().status)
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

private class RecordingProjectFavoriteRepository(initial: Set<String>) : ProjectFavoriteRepository {
    private val favorites = MutableStateFlow(initial)
    val writes = mutableListOf<Triple<String, String, Boolean>>()

    override fun observe(environmentId: String): Flow<Set<String>> = favorites

    override suspend fun setFavorite(
        environmentId: String,
        projectId: String,
        favorite: Boolean,
    ) {
        writes += Triple(environmentId, projectId, favorite)
        favorites.value = if (favorite) favorites.value + projectId else favorites.value - projectId
    }
}

private class RecordingInterfacePreferencesRepository(initial: InterfacePreferences) : InterfacePreferencesRepository {
    private val mutablePreferences = MutableStateFlow(initial)
    override val preferences: Flow<InterfacePreferences> = mutablePreferences
    val themes = mutableListOf<ThemePreference>()
    val accents = mutableListOf<AccentPreference>()
    val interfaceScales = mutableListOf<Float>()
    val codeScales = mutableListOf<Float>()

    override suspend fun setTheme(theme: ThemePreference) {
        themes += theme
        mutablePreferences.value = mutablePreferences.value.copy(theme = theme)
    }

    override suspend fun setAccent(accent: AccentPreference) {
        accents += accent
        mutablePreferences.value = mutablePreferences.value.copy(accent = accent)
    }

    override suspend fun setInterfaceScale(scale: Float) {
        interfaceScales += scale
        mutablePreferences.value = mutablePreferences.value.copy(interfaceScale = scale)
    }

    override suspend fun setFollowUpBehavior(behavior: de.chennemann.agentic.domain.preferences.FollowUpBehavior) { mutablePreferences.value = mutablePreferences.value.copy(followUpBehavior = behavior) }
    override suspend fun setCodeScale(scale: Float) {
        codeScales += scale
        mutablePreferences.value = mutablePreferences.value.copy(codeScale = scale)
    }

    override suspend fun setImageOpenAction(action: de.chennemann.agentic.domain.preferences.ImageOpenAction) {
        mutablePreferences.value = mutablePreferences.value.copy(imageOpenAction = action)
    }
}

private class RecordingEnvironmentCacheActions : EnvironmentCacheActions {
    val cleared = mutableListOf<String>()
    var failure: Throwable? = null
    override suspend fun usage(environmentId: String) = EnvironmentCacheUsage(
        environmentId,
        listOf(
            CacheCategoryUsage("Projections: shell", 2, 120, false),
            CacheCategoryUsage("Preferences", 1, 40, false),
            CacheCategoryUsage("Unsent drafts", 0, 0, true),
            CacheCategoryUsage("Pending command outbox", 1, 0, true),
        ),
    )
    override suspend fun clear(environmentId: String) {
        cleared += environmentId
        failure?.let { throw it }
    }
}

private class FakeSharedTextImportRepository(initial: PendingSharedText?) : SharedTextImportRepository {
    private val mutablePending = MutableStateFlow(initial)
    private val mutableError = MutableStateFlow<String?>(null)
    override val pending: Flow<PendingSharedText?> = mutablePending
    override val error: Flow<String?> = mutableError
    val discarded = mutableListOf<String>()
    var importFailure: Throwable? = null

    override suspend fun receive(result: SharedTextParseResult) = Unit

    override suspend fun markImported(fingerprint: String) {
        importFailure?.let { throw it }
        if (mutablePending.value?.fingerprint == fingerprint) mutablePending.value = null
    }

    override suspend fun discard(fingerprint: String) {
        discarded += fingerprint
        if (mutablePending.value?.fingerprint == fingerprint) mutablePending.value = null
    }

    override suspend fun clearError() {
        mutableError.value = null
    }
}

private class RecordingShortcutPublisher : DynamicShortcutPublisher {
    var latest: List<DynamicShortcutSpec> = emptyList()
    override fun publish(shortcuts: List<DynamicShortcutSpec>) {
        latest = shortcuts
    }
}

private class FakeShortcutRouteInbox : ShortcutRouteInbox {
    private val mutableRoutes = kotlinx.coroutines.flow.MutableSharedFlow<ShortcutParseResult>(extraBufferCapacity = 1)
    override val routes: Flow<ShortcutParseResult> = mutableRoutes
    override fun receive(result: ShortcutParseResult) {
        mutableRoutes.tryEmit(result)
    }
}

private class FakeConnectionController : ConnectionController {
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Live)
    var woken = false
    var pendingCommandsRetried = false

    override fun wake() {
        woken = true
    }

    override fun retryPendingCommands() {
        pendingCommandsRetried = true
    }
}

private class RecordingThreadActions(
    private val repository: FakeOrchestrationRepository,
) : ThreadActions {
    val selectedProjects = mutableListOf<String?>()
    val selectedThreads = mutableListOf<String?>()
    val settledThreads = mutableListOf<String>()
    val unsettledThreads = mutableListOf<String>()
    val snoozedThreads = mutableListOf<Pair<String, String>>()
    val wokenThreads = mutableListOf<String>()
    var wakeFailure: Throwable? = null
    val deletedThreads = mutableListOf<String>()
    var deleteFailure: Throwable? = null

    override suspend fun selectProject(projectId: String?) {
        selectedProjects += projectId
        repository.selectedProjectId.value = projectId
        repository.focusedThreadId.value = null
    }

    override suspend fun selectThread(threadId: String?) {
        selectedThreads += threadId
        repository.shell.value.value
            ?.threads
            ?.firstOrNull { it.id == threadId }
            ?.let { repository.selectedProjectId.value = it.projectId }
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

    override suspend fun snooze(threadId: String, snoozedUntil: String): DispatchResult {
        snoozedThreads += threadId to snoozedUntil
        return DispatchResult(1)
    }

    override suspend fun wake(threadId: String): DispatchResult {
        wokenThreads += threadId
        wakeFailure?.let { throw it }
        return DispatchResult(1)
    }

    override suspend fun delete(threadId: String): DispatchResult {
        deletedThreads += threadId
        deleteFailure?.let { throw it }
        return DispatchResult(1)
    }
}

private class FakeCommandOutbox : CommandOutbox {
    val entries = mutableListOf<OutboxCommand>()
    override fun observe(environmentId: String): Flow<List<OutboxCommand>> = MutableStateFlow(entries)
    override suspend fun enqueue(environmentId: String, command: ClientOrchestrationCommand) = Unit
    override suspend fun commands(environmentId: String): List<OutboxCommand> = entries.toList()
    override suspend fun markPending(environmentId: String, commandId: String) = Unit
    override suspend fun markFailed(environmentId: String, commandId: String, error: String) = Unit
    override suspend fun remove(environmentId: String, commandId: String) = Unit
}

private class ReactiveCommandOutbox(vararg initial: OutboxCommand) : CommandOutbox {
    private val state = MutableStateFlow(initial.toList())
    override fun observe(environmentId: String): Flow<List<OutboxCommand>> = state
    override suspend fun enqueue(environmentId: String, command: ClientOrchestrationCommand) = Unit
    override suspend fun commands(environmentId: String): List<OutboxCommand> = state.value
    override suspend fun markPending(environmentId: String, commandId: String) = Unit
    override suspend fun markFailed(environmentId: String, commandId: String, error: String) = Unit
    override suspend fun remove(environmentId: String, commandId: String) {
        state.value = state.value.filterNot { it.command.commandId == commandId }
    }
}

private class NoOpChatActions(
    private val startTurnFailure: Throwable? = null,
    var continuePlanFailure: Throwable? = null,
    var terminateSessionFailure: Throwable? = null,
) : ChatActions {
    val startTurnCalls = mutableListOf<StartTurnCall>()
    val planContinuations = mutableListOf<Pair<String, String>>()
    val interruptedTurns = mutableListOf<Pair<String, String?>>()
    val terminatedSessions = mutableListOf<String>()
    var userInputFailure: Throwable? = null
    val userInputResponses = mutableListOf<JsonObject>()

    override suspend fun startTurn(
        threadId: String?,
        projectId: String,
        prompt: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
        workspace: NewThreadWorkspace,
        attachments: List<de.chennemann.agentic.t3.contract.UploadChatAttachment>,
        context: kotlinx.serialization.json.JsonObject?,
    ): StartTurnResult {
        startTurnFailure?.let { throw it }
        startTurnCalls += StartTurnCall(
            threadId,
            projectId,
            prompt,
            modelSelection,
            runtimeMode,
            attachments,
            context,
        )
        return StartTurnResult(DispatchResult(1), threadId ?: "new-thread")
    }

    override suspend fun interrupt(
        threadId: String,
        turnId: String?,
    ): DispatchResult {
        interruptedTurns += threadId to turnId
        return DispatchResult(1)
    }

    override suspend fun terminateSession(threadId: String): DispatchResult {
        terminatedSessions += threadId
        terminateSessionFailure?.let { throw it }
        return DispatchResult(1)
    }

    override suspend fun respondToApproval(
        threadId: String,
        requestId: String,
        decision: String,
    ) = DispatchResult(1)

    override suspend fun respondToUserInput(
        threadId: String,
        requestId: String,
        answers: JsonObject,
    ): DispatchResult {
        userInputFailure?.let { throw it }
        userInputResponses += answers
        return DispatchResult(1)
    }

    override suspend fun setRuntimeMode(
        threadId: String,
        runtimeMode: String,
    ) = DispatchResult(1)

    override suspend fun continuePlan(
        threadId: String,
        planId: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
    ): DispatchResult {
        planContinuations += threadId to planId
        continuePlanFailure?.let { throw it }
        return DispatchResult(1)
    }
}

private data class StartTurnCall(
    val threadId: String?,
    val projectId: String,
    val prompt: String,
    val modelSelection: ModelSelection,
    val runtimeMode: String,
    val attachments: List<de.chennemann.agentic.t3.contract.UploadChatAttachment>,
    val context: JsonObject? = null,
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
    override val clientConfig = MutableStateFlow(ProjectionState<ServerConfig>())
    override val shell = MutableStateFlow(ProjectionState<OrchestrationShellSnapshot>())
    override val focusedThread = MutableStateFlow(ProjectionState<OrchestrationThreadDetailSnapshot>())
    override val focusedThreadId = MutableStateFlow<String?>(null)
    override val selectedProjectId = MutableStateFlow<String?>(null)

    override suspend fun loadCached(environmentId: String) = Unit

    override suspend fun setClientConfig(
        environmentId: String,
        config: ServerConfig,
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
        threadId: String,
        item: OrchestrationThreadStreamItem,
    ): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>> = Reduction.Ignored(focusedThread.value)

    override suspend fun clearEnvironment(environmentId: String) = Unit
}

private class RecordingProjectActions : ProjectActions {
    val removed = mutableListOf<String>()

    override suspend fun create(source: String): String = "project-created"

    override suspend fun rename(projectId: String, title: String) = Unit

    override suspend fun remove(projectId: String): String {
        removed += projectId
        return "project-2"
    }
}

private class RecordingProjectDestinationBrowser : ProjectDestinationBrowser {
    val requests = mutableListOf<String>()
    var failure: Exception? = null

    override suspend fun browse(partialPath: String, enterDirectory: Boolean): ProjectDestinationListing {
        requests += partialPath
        failure?.let { throw it }
        return ProjectDestinationListing(
            query = partialPath,
            parentPath = "/srv",
            destinations = listOf(ProjectDestination("alpha", "/srv/alpha")),
        )
    }
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
        value = ServerConfig(
            environment = ExecutionEnvironmentDescriptor(
                environmentId = "environment",
                label = "Environment",
                platform = EnvironmentPlatform("linux", "x64"),
                serverVersion = "1",
                capabilities = ExecutionEnvironmentCapabilities(),
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
                            capabilities = T3Json.parseToJsonElement(
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

private fun proposedPlan(
    id: String,
    markdown: String,
    updatedAt: String = "2026-08-06T01:00:00Z",
) = ProposedPlan(
    id = id,
    turnId = "turn-1",
    planMarkdown = markdown,
    createdAt = updatedAt,
    updatedAt = updatedAt,
)

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
    payload = T3Json.parseToJsonElement(
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
): kotlinx.serialization.json.JsonElement = T3Json.parseToJsonElement(
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

private class QueueFixture(scope: CoroutineScope) {
    val repository = submissionRepository(ModelSelection("provider", "model"))
    val environments = FakeViewModelEnvironmentRepository()
    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Live)
    val connection = object : ConnectionController {
        override val state = connectionState
        override fun wake() = Unit
        override fun retryPendingCommands() = Unit
    }
    val sent = mutableListOf<de.chennemann.agentic.t3.contract.ClientOrchestrationCommand.StartTurn>()
    var failure: Exception? = null
    val queue = de.chennemann.agentic.domain.orchestration.MessageQueueService(
        repository, environments, connection,
        object : de.chennemann.agentic.domain.orchestration.CommandDispatcher {
            override suspend fun dispatch(command: de.chennemann.agentic.t3.contract.ClientOrchestrationCommand): DispatchResult {
                sent += command as de.chennemann.agentic.t3.contract.ClientOrchestrationCommand.StartTurn
                failure?.let { throw it }
                return DispatchResult(requireNotNull(repository.shell.value.sequence) + 1)
            }
        }, scope,
    )
    fun enqueue(text: String) = queue.enqueue("environment", "thread-1", text, ModelSelection("provider", "model"), "full-access", emptyList())
    fun setRunning(running: Boolean, sequence: Long, approval: Boolean = false) {
        val current = repository.shell.value
        repository.shell.value = current.copy(sequence = sequence, value = current.value?.copy(
            threads = current.value.threads.map { thread ->
                if (thread.id == "thread-1") thread.copy(
                    session = ThreadSession("thread-1", if (running) "running" else "ready", activeTurnId = if (running) "active" else null, updatedAt = "now"),
                    hasPendingApprovals = approval,
                ) else thread
            },
        ))
    }
}

private class RecordingComposerContext : de.chennemann.agentic.domain.composer.ComposerContextActions {
    val queries = mutableListOf<Pair<String, String>>()
    val prepared = mutableListOf<de.chennemann.agentic.domain.composer.ComposerDocument>()
    override fun maxPasteBytes() = 50L * 1024 * 1024
    override fun inlineContextAvailable() = true
    override suspend fun suggestions(kind: String, query: String, instanceId: String?): List<de.chennemann.agentic.domain.composer.ComposerSuggestion> {
        queries += kind to query
        return listOf(de.chennemann.agentic.domain.composer.ComposerSuggestion("path:src/Main.kt", "path", "Main.kt", "src/Main.kt", "src/Main.kt"))
    }
    override suspend fun prepare(environmentId: String, document: de.chennemann.agentic.domain.composer.ComposerDocument): de.chennemann.agentic.domain.composer.PreparedComposerContext {
        prepared += document
        val uploads = document.retained().pastes.map { de.chennemann.agentic.t3.contract.UploadChatAttachment(type = "file", id = it.id, name = it.name, mimeType = "text/plain", sizeBytes = it.text.toByteArray().size.toLong()) }
        return de.chennemann.agentic.domain.composer.PreparedComposerContext(uploads, document.referencedRecords())
    }
}

private class ComposerTestCredentials : de.chennemann.agentic.data.auth.CredentialStore {
    override suspend fun read(environmentId: String) = "test-token"
    override suspend fun write(environmentId: String, bearerToken: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
}

private class RecordingComposerRpc : de.chennemann.agentic.data.t3.ComposerRpcClient {
    val workspaces = mutableListOf<Pair<String, String>>()
    var onUpload: () -> Unit = {}
    override suspend fun searchComposerPaths(baseUrl: String, token: String, cwd: String, query: String) = de.chennemann.agentic.t3.contract.WorkspaceEntriesResult(emptyList(), false)
    override suspend fun composerProviders(baseUrl: String, token: String, instanceId: String, cwd: String): de.chennemann.agentic.t3.contract.ProviderUpdatedResult {
        workspaces += instanceId to cwd
        val skills = listOf(de.chennemann.agentic.t3.contract.ProviderSkill("allowed", "/skills/a", true), de.chennemann.agentic.t3.contract.ProviderSkill("disabled", "/skills/b", false), de.chennemann.agentic.t3.contract.ProviderSkill("agent-only", "/skills/c", true, userInvocable = false))
        return de.chennemann.agentic.t3.contract.ProviderUpdatedResult(listOf(ProviderInstance(instanceId, workspaceSnapshots = listOf(de.chennemann.agentic.t3.contract.ProviderWorkspaceSnapshot(cwd, skills)))))
    }
    override suspend fun uploadPastedText(baseUrl: String, token: String, name: String, text: String): String {
        onUpload()
        return "uploaded-file"
    }
}
