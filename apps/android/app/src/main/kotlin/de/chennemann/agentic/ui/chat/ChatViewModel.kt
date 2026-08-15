package de.chennemann.agentic.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.connection.ConnectionState
import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentRemover
import de.chennemann.agentic.domain.environment.EnvironmentSelector
import de.chennemann.agentic.domain.orchestration.ChatActions
import de.chennemann.agentic.domain.orchestration.ChatService
import de.chennemann.agentic.domain.orchestration.CommandOutbox
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.ThreadService
import de.chennemann.agentic.domain.orchestration.ThreadActions
import de.chennemann.agentic.domain.preferences.ComposerDraftRepository
import de.chennemann.agentic.domain.preferences.FavoriteModelId
import de.chennemann.agentic.domain.preferences.ModelFavoriteRepository
import de.chennemann.agentic.domain.preferences.InterfacePreferences
import de.chennemann.agentic.domain.preferences.InterfacePreferencesRepository
import de.chennemann.agentic.domain.preferences.SupportedDisplayScales
import de.chennemann.agentic.domain.sharing.PendingSharedText
import de.chennemann.agentic.ui.chat.workflow.CacheAdministration
import de.chennemann.agentic.ui.chat.workflow.CacheAdministrationIntent
import de.chennemann.agentic.ui.chat.workflow.CacheAdministrationState
import de.chennemann.agentic.ui.chat.workflow.ProjectWorkflow
import de.chennemann.agentic.ui.chat.workflow.ProjectWorkflowIntent
import de.chennemann.agentic.ui.chat.workflow.ProjectWorkflowState
import de.chennemann.agentic.ui.chat.workflow.SharedTextWorkflow
import de.chennemann.agentic.ui.chat.workflow.SharedTextWorkflowIntent
import de.chennemann.agentic.ui.chat.workflow.SharedTextWorkflowState
import de.chennemann.agentic.ui.chat.workflow.ShortcutWorkflow
import de.chennemann.agentic.domain.voice.GroqApiKeyStore
import de.chennemann.agentic.domain.voice.VoiceInputService
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.OrchestrationActivity
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.T3Json
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val environments: EnvironmentRepository,
    private val repository: OrchestrationRepository,
    private val connection: ConnectionController,
    private val environmentService: EnvironmentSelector,
    private val threads: ThreadActions,
    private val chat: ChatActions,
    private val mappingDispatcher: CoroutineDispatcher,
    private val composerDrafts: ComposerDraftRepository? = null,
    private val modelFavorites: ModelFavoriteRepository? = null,
    private val groqApiKeys: GroqApiKeyStore? = null,
    private val voiceInput: VoiceInputService? = null,
    private val environmentRemover: EnvironmentRemover? = null,
    private val projectWorkflow: ProjectWorkflow? = null,
    private val commandOutbox: CommandOutbox? = null,
    private val sharedTextWorkflow: SharedTextWorkflow? = null,
    private val shortcutWorkflow: ShortcutWorkflow? = null,
    private val interfacePreferences: InterfacePreferencesRepository? = null,
    private val cacheAdministration: CacheAdministration? = null,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())
    private val mutableDraft = MutableStateFlow("")
    val draft = mutableDraft.asStateFlow()
    private val draftCache = mutableMapOf<DraftKey, String>()
    private val dirtyDraftKeys = mutableSetOf<DraftKey>()
    private val draftWrites = Channel<DraftWrite>(Channel.UNLIMITED)
    private val currentDraftKey = combine(
        environments.activeEnvironment,
        repository.focusedThreadId,
        repository.selectedProjectId,
    ) { environment, threadId, projectId ->
        when {
            environment == null -> null
            threadId != null -> DraftKey(environment.id, threadId)
            projectId != null -> DraftKey(environment.id, "$NewThreadDraftPrefix$projectId")
            else -> null
        }
    }.distinctUntilChanged()
    private var displayedDraftKey: DraftKey? = null
    private var draftSelectionInitialized = false
    private var unthreadedDraft = ""
    private var pendingDraftWrite: DraftWrite? = null
    private var pendingDraftWriteJob: Job? = null
    private val orchestration = combine(
        repository.clientConfig,
        repository.shell,
        repository.focusedThread,
        repository.selectedProjectId,
        repository.focusedThreadId,
    ) { config, shell, thread, projectId, threadId ->
        OrchestrationBundle(config, shell, thread, projectId, threadId)
    }
    private val environment = combine(
        environments.environments,
        environments.activeEnvironment,
        connection.state,
    ) { all, active, connection ->
        EnvironmentBundle(all, active, connection)
    }
    private val favorites = environments.activeEnvironment.flatMapLatest { active ->
        if (active == null || modelFavorites == null) {
            flowOf(emptyList())
        } else {
            modelFavorites.observe(active.id)
        }
    }
    private val workflowPresentation = combine(
        projectWorkflow?.state ?: flowOf(ProjectWorkflowState()),
        sharedTextWorkflow?.state ?: flowOf(SharedTextWorkflowState()),
        cacheAdministration?.state ?: flowOf(CacheAdministrationState()),
        shortcutWorkflow?.error ?: flowOf(null),
    ) { project, shared, cache, shortcutError -> WorkflowPresentation(project, shared, cache, shortcutError) }
    private val durableWork = environments.activeEnvironment.flatMapLatest { active ->
        if (active == null || commandOutbox == null) flowOf(emptyList()) else commandOutbox.observeStatuses(active.id)
    }
    private val displayAndWork = combine(
        interfacePreferences?.preferences ?: flowOf(InterfacePreferences()),
        durableWork,
    ) { display, work -> display to work }

    init {
        projectWorkflow?.let { workflow ->
            viewModelScope.launch {
                workflow.state.map { it.selectedProjectId }.distinctUntilChanged().collect { selectedProjectId ->
                    if (selectedProjectId != null) update { copy(pickerProjectId = selectedProjectId) }
                }
            }
        }
        sharedTextWorkflow?.let { workflow ->
            workflow.start(viewModelScope)
        }
        shortcutWorkflow?.let { workflow ->
            workflow.start(viewModelScope)
        }
        composerDrafts?.let { drafts ->
            viewModelScope.launch {
                for (write in draftWrites) {
                    runCatching {
                        drafts.setDraft(
                            environmentId = write.key.environmentId,
                            threadId = write.key.threadId,
                            draft = write.value,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            currentDraftKey.collectLatest { key -> selectDraft(key, composerDrafts) }
        }
        viewModelScope.launch {
            repository.focusedThread.collect { state ->
                val pendingPlanId = local.value.planContinuationId ?: return@collect
                val plan = state.value?.thread?.proposedPlans?.firstOrNull { it.id == pendingPlanId }
                if (plan == null || plan.implementationThreadId != null || plan.implementedAt != null) {
                    update { copy(planContinuationId = null) }
                }
            }
        }
        viewModelScope.launch {
            combine(repository.focusedThread, repository.shell) { thread, shell -> thread to shell }.collect { (thread, shell) ->
                val deletingId = local.value.deletionThreadId?.takeIf { local.value.deletionInProgress } ?: return@collect
                val detailGone = thread.value?.thread?.id != deletingId
                val shellGone = shell.value?.threads?.none { it.id == deletingId } == true
                if (detailGone && shellGone) {
                    val fallback = shell.value.threads.firstOrNull()?.id
                    update { copy(deletionThreadId = null, deletionInProgress = false, deletionVisible = false) }
                    threads.selectThread(fallback)
                }
            }
        }
        viewModelScope.launch {
            repository.focusedThread.collect { state ->
                val terminatingThreadId = local.value.sessionTerminatingThreadId ?: return@collect
                val detail = state.value?.thread
                if (detail?.id == terminatingThreadId && detail.session?.status !in ActiveSessionStatuses) {
                    update {
                        copy(
                            sessionTerminatingThreadId = null,
                            sessionTerminalThreadId = terminatingThreadId,
                            sessionTerminationError = null,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.focusedThread.collect { state ->
                val pendingThreadId = local.value.snoozeActionThreadId ?: return@collect
                val detail = state.value?.thread ?: return@collect
                if (detail.id != pendingThreadId) return@collect
                val confirmed = when (local.value.snoozeAction) {
                    SnoozeAction.SNOOZE -> detail.snoozedUntil == local.value.snoozeTargetUntil
                    SnoozeAction.WAKE -> detail.snoozedUntil == null
                    null -> false
                }
                if (confirmed) {
                    update {
                        copy(
                            snoozeActionThreadId = null,
                            snoozeAction = null,
                            snoozeTargetUntil = null,
                            snoozeError = null,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            var activeThreadIds: Set<String>? = null
            combine(repository.shell, repository.focusedThreadId) { shell, focusedThreadId ->
                shell.value?.threads.orEmpty() to focusedThreadId
            }.collect { (threadShells, focusedThreadId) ->
                val nextActiveThreadIds = threadShells
                    .filter { it.session?.status in ActiveSessionStatuses }
                    .mapTo(mutableSetOf()) { it.id }
                val completedThreadIds = activeThreadIds
                    ?.minus(nextActiveThreadIds)
                    .orEmpty()
                activeThreadIds = nextActiveThreadIds
                val availableThreadIds = threadShells.mapTo(mutableSetOf()) { it.id }
                update {
                    copy(
                        unreadThreadIds = (
                            unreadThreadIds
                                .intersect(availableThreadIds) +
                                completedThreadIds.filterNot { it == focusedThreadId }
                            ) - setOfNotNull(focusedThreadId),
                    )
                }
            }
        }
    }

    private var mappedTimelineInput: TimelineInput? = null
    private var mappedTimeline: List<ChatTimelineItemUi> = emptyList()

    private val mappedState = combine(
        orchestration,
        environment,
        local.map(LocalState::structural).distinctUntilChanged(),
        favorites,
    ) { orchestration, environment, local, favorites ->
        MappedInput(orchestration, environment, local, favorites)
    }.conflate().map { input ->
        val timelineInput = input.timelineInput()
        if (timelineInput != mappedTimelineInput) {
            mappedTimelineInput = timelineInput
            mappedTimeline = timelineInput.detail?.let { timeline(it, input.local) }.orEmpty()
        }
        mapState(input.orchestration, input.environment, input.local, input.favorites, mappedTimeline)
    }.flowOn(mappingDispatcher)

    val state = combine(
        mappedState,
        local,
        groqApiKeys?.configured ?: flowOf(false),
        workflowPresentation,
        displayAndWork,
    ) { mapped, local, groqConfigured, workflows, displayAndWork ->
        val display = displayAndWork.first
        val shared = workflows.shared
        val project = workflows.project
        val cache = workflows.cache
        mapped.copy(
            composer = mapped.composer.copy(
                sending = local.sending,
                errorMessage = local.commandError,
                voiceInputAvailable = groqConfigured && voiceInput != null,
                voiceInputStatus = local.voiceInputStatus,
            ),
            groqSettings = GroqSettingsUiState(
                dialogVisible = local.groqSettingsVisible,
                apiKeyConfigured = groqConfigured,
                saving = local.groqSettingsSaving,
                errorMessage = local.groqSettingsError,
            ),
            projectCreation = if (project.creationVisible) {
                ProjectCreationUi(
                    source = project.creationSource,
                    creating = project.creationSaving,
                    errorMessage = project.creationError,
                    browsePath = project.creationBrowsePath,
                    parentPath = project.creationParentPath,
                    destinations = project.creationDestinations.map { ProjectDestinationUi(it.name, it.fullPath) },
                    browsing = project.creationBrowsing,
                )
            } else null,
            projectRename = project.renameId?.let {
                ProjectRenameUi(it, project.renamePreviousTitle, project.renameTitle, project.renameSaving, project.renameError)
            },
            projectRemoval = project.removalId?.let {
                ProjectRemovalUi(it, project.removalTitle, project.removalSaving, project.removalError)
            },
            sharedTextImport = shared.pending?.let { pending ->
                SharedTextImportUi(
                    fingerprint = pending.fingerprint,
                    text = pending.text,
                    environments = mapped.environmentPicker.environments.map {
                        ProjectPickerItemUi(it.id, it.label, it.supportingText)
                    },
                    projects = mapped.threadPicker.projects,
                    environmentId = shared.environmentId,
                    projectId = shared.projectId,
                    importing = shared.saving,
                    errorMessage = shared.error,
                )
            },
            sharedTextImportError = shared.error.takeIf { shared.pending == null },
            shortcutError = workflows.shortcutError,
            interfaceSettings = InterfaceSettingsUi(display.theme, display.interfaceScale, display.codeScale),
            cacheClearance = cache.environmentId?.let {
                CacheClearanceUi(it, cache.categories, cache.clearableBytes, cache.busy, cache.cleared, cache.error)
            },
            durableWork = displayAndWork.second.map {
                DurableWorkUi(it.commandId, it.label, it.threadId, it.status.name == "FAILED", it.attemptCount, it.errorMessage, it.awaitsReplay)
            },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ChatUiState(
            title = "New thread",
            environmentLabel = "T3",
            projectLabel = null,
            threadId = null,
            timeline = emptyList(),
            connection = ChatConnectionUi.Connecting(),
            composer = ComposerUiState(enabled = false),
        ),
    )

    fun onEvent(event: ChatUiEvent) {
        when (event) {
            is ChatUiEvent.DraftChanged -> setDraft(event.value)
            ChatUiEvent.DraftDiscardRequested -> setDraft(
                displayedDraftKey,
                "",
                persistImmediately = true,
            )
            is ChatUiEvent.ProposedPlanContinueRequested -> continueProposedPlan(event.planId)
            ChatUiEvent.MessageSubmitted -> submitMessage()
            ChatUiEvent.VoiceInputPressed -> handleVoiceInput()
            ChatUiEvent.VoiceInputCancelled -> cancelVoiceInput()
            ChatUiEvent.MicrophonePermissionDenied -> update {
                copy(commandError = "Microphone permission is required for voice input.")
            }
            ChatUiEvent.GroqSettingsRequested -> update {
                copy(
                    activePicker = null,
                    groqSettingsVisible = true,
                    groqSettingsError = null,
                )
            }
            ChatUiEvent.GroqSettingsDismissed -> update {
                copy(
                    groqSettingsVisible = false,
                    groqSettingsError = null,
                )
            }
            is ChatUiEvent.GroqApiKeySaved -> saveGroqApiKey(event.apiKey)
            ChatUiEvent.GroqApiKeyRemoved -> removeGroqApiKey()
            ChatUiEvent.TurnInterruptRequested -> launchCommand {
                val detail = repository.focusedThread.value.value?.thread ?: return@launchCommand
                chat.interrupt(detail.id, detail.latestTurn?.turnId ?: return@launchCommand)
            }
            ChatUiEvent.SessionTerminationRequested -> terminateSession()

            is ChatUiEvent.ProviderModelSelected -> update {
                copy(providerModelId = event.id, activePicker = null)
            }

            is ChatUiEvent.ProviderModelFavoriteChanged -> {
                val activeEnvironmentId = environments.activeEnvironment.value?.id
                val model = event.id.toFavoriteModelId()
                if (activeEnvironmentId != null && model != null && modelFavorites != null) {
                    launchCommand {
                        modelFavorites.setFavorite(activeEnvironmentId, model, event.favorite)
                    }
                }
            }

            is ChatUiEvent.ProviderSelectOptionSelected -> update {
                val modelId = selectedModelId() ?: return@update this
                copy(
                    providerOptionValues = providerOptionValues +
                        (optionValueKey(modelId, event.optionId) to JsonPrimitive(event.valueId)),
                )
            }

            is ChatUiEvent.ProviderBooleanOptionChanged -> update {
                val modelId = selectedModelId() ?: return@update this
                copy(
                    providerOptionValues = providerOptionValues +
                        (optionValueKey(modelId, event.optionId) to JsonPrimitive(event.selected)),
                )
            }

            is ChatUiEvent.RuntimeModeSelected -> {
                update { copy(runtimeModeId = event.id, activePicker = null) }
                repository.focusedThreadId.value?.let { threadId ->
                    launchCommand { chat.setRuntimeMode(threadId, event.id) }
                }
            }

            is ChatUiEvent.SlashCommandSelected -> {
                setDraft("/${event.name} ")
                update { copy(activePicker = null) }
            }

            is ChatUiEvent.PickerRequested -> update {
                copy(
                    activePicker = event.picker,
                    pickerProjectId = if (event.picker == ChatPickerUi.PROJECT_THREAD) {
                        repository.selectedProjectId.value
                    } else {
                        pickerProjectId
                    },
                )
            }

            ChatUiEvent.PickerDismissed -> update {
                copy(activePicker = null, pickerProjectId = null)
            }
            is ChatUiEvent.EnvironmentSelected -> launchCommand {
                environmentService.select(event.environmentId)
                update { copy(activePicker = null, pickerProjectId = null) }
            }

            ChatUiEvent.PairEnvironmentRequested -> Unit
            is ChatUiEvent.EnvironmentRemovalRequested -> {
                val environment = environments.environments.value.firstOrNull {
                    it.id == event.environmentId
                } ?: return
                update {
                    copy(
                        environmentRemovalId = environment.id,
                        environmentRemovalLabel = environment.label,
                        environmentRemovalError = null,
                    )
                }
            }
            ChatUiEvent.EnvironmentRemovalDismissed -> update {
                if (environmentRemovalSaving) this else copy(
                    environmentRemovalId = null,
                    environmentRemovalLabel = null,
                    environmentRemovalError = null,
                )
            }
            ChatUiEvent.EnvironmentRemovalConfirmed -> {
                val environmentId = local.value.environmentRemovalId ?: return
                val remover = environmentRemover ?: return
                update { copy(environmentRemovalSaving = true, environmentRemovalError = null) }
                viewModelScope.launch {
                    runCatching { remover.remove(environmentId) }
                        .onSuccess {
                            update {
                                copy(
                                    activePicker = null,
                                    environmentRemovalId = null,
                                    environmentRemovalLabel = null,
                                    environmentRemovalSaving = false,
                                )
                            }
                        }
                        .onFailure { cause ->
                            update {
                                copy(
                                    environmentRemovalSaving = false,
                                    environmentRemovalError = cause.message ?: "Environment removal failed.",
                                )
                            }
                        }
                }
            }
            ChatUiEvent.ProjectCreationRequested -> acceptProject(ProjectWorkflowIntent.RequestCreation)
            is ChatUiEvent.ProjectCreationSourceChanged -> acceptProject(ProjectWorkflowIntent.ChangeCreationSource(event.value))
            ChatUiEvent.ProjectCreationBrowseRequested -> acceptProject(ProjectWorkflowIntent.BrowseCreationSource)
            is ChatUiEvent.ProjectCreationDestinationOpened ->
                acceptProject(ProjectWorkflowIntent.OpenCreationDestination(event.path))
            ChatUiEvent.ProjectCreationParentOpened -> acceptProject(ProjectWorkflowIntent.OpenCreationParent)
            ChatUiEvent.ProjectCreationDismissed -> acceptProject(ProjectWorkflowIntent.DismissCreation)
            ChatUiEvent.ProjectCreationConfirmed -> acceptProject(ProjectWorkflowIntent.ConfirmCreation)
            is ChatUiEvent.ProjectRenameRequested -> acceptProject(ProjectWorkflowIntent.RequestRename(event.projectId))
            is ChatUiEvent.ProjectRenameTitleChanged -> acceptProject(ProjectWorkflowIntent.ChangeRenameTitle(event.value))
            ChatUiEvent.ProjectRenameDismissed -> acceptProject(ProjectWorkflowIntent.DismissRename)
            ChatUiEvent.ProjectRenameConfirmed -> acceptProject(ProjectWorkflowIntent.ConfirmRename)
            is ChatUiEvent.ProjectRemovalRequested -> acceptProject(ProjectWorkflowIntent.RequestRemoval(event.projectId))
            ChatUiEvent.ProjectRemovalDismissed -> acceptProject(ProjectWorkflowIntent.DismissRemoval)
            ChatUiEvent.ProjectRemovalConfirmed -> acceptProject(ProjectWorkflowIntent.ConfirmRemoval)
            is ChatUiEvent.ProjectSelected -> launchCommand {
                threads.selectProject(event.projectId)
                update {
                    copy(
                        activePicker = ChatPickerUi.PROJECT_THREAD,
                        pickerProjectId = event.projectId,
                    )
                }
            }

            is ChatUiEvent.ProjectQuickSwitchRequested -> quickSwitchProject(event.projectId)
            is ChatUiEvent.ProjectThreadsRequested -> update {
                copy(
                    activePicker = ChatPickerUi.PROJECT_THREAD,
                    pickerProjectId = event.projectId,
                )
            }

            is ChatUiEvent.ThreadSelected -> selectThread(event.threadId)
            is ChatUiEvent.ThreadSettleRequested -> launchCommand {
                threads.settle(event.threadId)
            }
            is ChatUiEvent.ThreadUnsettleRequested -> launchCommand {
                threads.unsettle(event.threadId)
            }
            is ChatUiEvent.PickerThreadUnarchiveRequested -> launchCommand {
                threads.unarchive(event.threadId)
            }

            is ChatUiEvent.ArchivedThreadsVisibilityChanged -> update {
                copy(showArchived = event.visible)
            }

            is ChatUiEvent.SettledThreadsVisibilityChanged -> update {
                copy(showSettled = event.visible)
            }

            ChatUiEvent.NewThreadRequested -> launchCommand {
                val projectId = local.value.pickerProjectId
                if (projectId != null && projectId != repository.selectedProjectId.value) {
                    threads.selectProject(projectId)
                }
                threads.selectThread(null)
                update { copy(activePicker = null, pickerProjectId = null) }
            }
            ChatUiEvent.RenameThreadRequested -> update {
                copy(
                    renameDraft = state.value.title,
                    renameVisible = true,
                    activePicker = null,
                )
            }

            is ChatUiEvent.RenameThreadDraftChanged -> update { copy(renameDraft = event.value) }
            ChatUiEvent.RenameThreadDismissed -> update { copy(renameVisible = false) }
            ChatUiEvent.RenameThreadConfirmed -> renameThread()
            ChatUiEvent.ArchiveThreadRequested -> repository.focusedThreadId.value?.let { id ->
                update { copy(activePicker = null) }
                launchCommand { threads.archive(id) }
            }

            ChatUiEvent.UnarchiveThreadRequested -> repository.focusedThreadId.value?.let { id ->
                update { copy(activePicker = null) }
                launchCommand { threads.unarchive(id) }
            }

            is ChatUiEvent.ThreadSnoozeRequested -> changeSnooze(SnoozeAction.SNOOZE, event.snoozedUntil)
            ChatUiEvent.ThreadWakeRequested -> changeSnooze(SnoozeAction.WAKE)
            ChatUiEvent.DeleteThreadRequested -> openThreadDeletion()
            ChatUiEvent.DeleteThreadConfirmed -> confirmThreadDeletion()
            ChatUiEvent.DeleteThreadDismissed -> if (!local.value.deletionInProgress) {
                update { copy(deletionVisible = false, deletionThreadId = null, deletionError = null) }
            }
            is ChatUiEvent.SharedImportEnvironmentSelected -> acceptShared(SharedTextWorkflowIntent.SelectEnvironment(event.environmentId))
            is ChatUiEvent.SharedImportProjectSelected -> acceptShared(SharedTextWorkflowIntent.SelectProject(event.projectId))
            ChatUiEvent.SharedImportConfirmed -> acceptShared(SharedTextWorkflowIntent.Import)
            ChatUiEvent.SharedImportDiscarded -> acceptShared(SharedTextWorkflowIntent.Discard)
            ChatUiEvent.SharedImportErrorDismissed -> acceptShared(SharedTextWorkflowIntent.DismissError)
            ChatUiEvent.ShortcutErrorDismissed -> shortcutWorkflow?.dismissError()
            is ChatUiEvent.ThemeSelected -> viewModelScope.launch { interfacePreferences?.setTheme(event.value) }
            is ChatUiEvent.InterfaceScaleSelected -> if (event.value in SupportedDisplayScales) {
                viewModelScope.launch { interfacePreferences?.setInterfaceScale(event.value) }
            }
            is ChatUiEvent.CodeScaleSelected -> if (event.value in SupportedDisplayScales) {
                viewModelScope.launch { interfacePreferences?.setCodeScale(event.value) }
            }
            is ChatUiEvent.CacheInspectionRequested -> acceptCache(CacheAdministrationIntent.Inspect(event.environmentId))
            ChatUiEvent.CacheClearConfirmed -> acceptCache(CacheAdministrationIntent.Clear)
            ChatUiEvent.CacheClearDismissed -> acceptCache(CacheAdministrationIntent.Dismiss)
            ChatUiEvent.DurableWorkRetryRequested -> connection.retryPendingCommands()

            is ChatUiEvent.ApprovalDecisionSelected -> respondToApproval(event)
            is ChatUiEvent.UserInputTextChanged -> update {
                copy(
                    textAnswers = textAnswers + (
                        answerKey(event.requestId, event.questionId) to event.value
                        ),
                )
            }

            is ChatUiEvent.UserInputOptionToggled -> update {
                val key = answerKey(event.requestId, event.questionId)
                val current = optionAnswers[key].orEmpty()
                val next = if (event.optionId in current) current - event.optionId else current + event.optionId
                copy(optionAnswers = optionAnswers + (key to next))
            }

            is ChatUiEvent.UserInputSubmitted -> respondToUserInput(event.requestId)
            is ChatUiEvent.ActivityExpansionChanged,
            is ChatUiEvent.ActivityRetryRequested,
            -> Unit

            ChatUiEvent.ConnectionRetryRequested -> connection.wake()
        }
    }

    private fun handleVoiceInput() {
        val service = voiceInput ?: return
        if (groqApiKeys?.configured?.value != true) return
        when (local.value.voiceInputStatus) {
            VoiceInputStatusUi.IDLE -> {
                runCatching { service.startRecording() }
                    .onSuccess {
                        update {
                            copy(
                                voiceInputStatus = VoiceInputStatusUi.RECORDING,
                                commandError = null,
                            )
                        }
                    }
                    .onFailure {
                        update {
                            copy(commandError = it.message ?: "Unable to start recording.")
                        }
                    }
            }

            VoiceInputStatusUi.RECORDING -> {
                update {
                    copy(
                        voiceInputStatus = VoiceInputStatusUi.TRANSCRIBING,
                        commandError = null,
                    )
                }
                viewModelScope.launch {
                    runCatching { service.stopAndTranscribe() }
                        .onSuccess { transcript ->
                            setDraft(transcript)
                            update {
                                copy(
                                    voiceInputStatus = VoiceInputStatusUi.IDLE,
                                    commandError = null,
                                )
                            }
                        }
                        .onFailure {
                            update {
                                copy(
                                    voiceInputStatus = VoiceInputStatusUi.IDLE,
                                    commandError = it.message ?: "Unable to transcribe the recording.",
                                )
                            }
                        }
                }
            }

            VoiceInputStatusUi.TRANSCRIBING -> Unit
        }
    }

    private fun cancelVoiceInput() {
        if (local.value.voiceInputStatus != VoiceInputStatusUi.RECORDING) return
        voiceInput?.cancelRecording()
        update {
            copy(
                voiceInputStatus = VoiceInputStatusUi.IDLE,
                commandError = "Recording stopped because the app left the foreground.",
            )
        }
    }

    private fun saveGroqApiKey(apiKey: String) {
        val store = groqApiKeys ?: return
        val value = apiKey.trim()
        if (value.isEmpty()) {
            update { copy(groqSettingsError = "Enter a Groq API key.") }
            return
        }
        update {
            copy(
                groqSettingsSaving = true,
                groqSettingsError = null,
            )
        }
        viewModelScope.launch {
            runCatching { store.write(value) }
                .onSuccess {
                    update {
                        copy(
                            groqSettingsVisible = false,
                            groqSettingsSaving = false,
                            groqSettingsError = null,
                        )
                    }
                }
                .onFailure {
                    update {
                        copy(
                            groqSettingsSaving = false,
                            groqSettingsError = it.message ?: "Unable to save the Groq API key.",
                        )
                    }
                }
        }
    }

    private fun removeGroqApiKey() {
        val store = groqApiKeys ?: return
        if (local.value.voiceInputStatus == VoiceInputStatusUi.RECORDING) {
            voiceInput?.cancelRecording()
        }
        update {
            copy(
                voiceInputStatus = VoiceInputStatusUi.IDLE,
                groqSettingsSaving = true,
                groqSettingsError = null,
            )
        }
        viewModelScope.launch {
            runCatching { store.remove() }
                .onSuccess {
                    update {
                        copy(
                            groqSettingsVisible = false,
                            groqSettingsSaving = false,
                        )
                    }
                }
                .onFailure {
                    update {
                        copy(
                            groqSettingsSaving = false,
                            groqSettingsError = it.message ?: "Unable to remove the Groq API key.",
                        )
                    }
                }
        }
    }

    private fun submitMessage() {
        if (local.value.sending) return
        val draftKey = displayedDraftKey
        val prompt = mutableDraft.value.trim()
        if (prompt.isEmpty()) return
        val shell = repository.shell.value.value
            ?: return submissionUnavailable("Projects are not available yet.")
        val projectId = repository.selectedProjectId.value
            ?: shell.projects.firstOrNull()?.id
            ?: return submissionUnavailable("Choose a project before sending.")
        val selection = selectedModel()
            ?: return submissionUnavailable("Choose an available provider and model before sending.")
        val threadId = repository.focusedThreadId.value
        val detail = repository.focusedThread.value.value?.thread
            ?.takeIf { it.id == threadId }
        val runtimeMode = local.value.runtimeModeId
            ?: detail?.runtimeMode
            ?: DefaultRuntimeMode
        update { copy(sending = true, commandError = null) }
        launchCommand {
            val result = chat.startTurn(
                threadId = threadId,
                projectId = projectId,
                prompt = prompt,
                modelSelection = selection,
                runtimeMode = runtimeMode,
            )
            setDraft(draftKey, "", persistImmediately = true)
            update { copy(sending = false, commandError = null) }
            if (threadId == null) threads.selectThread(result.threadId)
        }
    }

    private fun continueProposedPlan(planId: String) {
        if (local.value.planContinuationId != null) return
        val detail = repository.focusedThread.value.value?.thread ?: return
        if (detail.session?.status in ActiveSessionStatuses) return
        val actionable = detail.proposedPlans
            .filter { it.implementationThreadId == null && it.implementedAt == null }
            .maxWithOrNull(compareBy<de.chennemann.agentic.t3.contract.ProposedPlan> { it.updatedAt }.thenBy { it.id })
            ?.takeIf { it.id == planId }
            ?: return
        val selection = selectedModel() ?: return
        val runtimeMode = local.value.runtimeModeId ?: detail.runtimeMode ?: DefaultRuntimeMode
        update {
            copy(
                planContinuationId = actionable.id,
                planContinuationError = null,
            )
        }
        viewModelScope.launch {
            try {
                chat.continuePlan(detail.id, actionable.id, selection, runtimeMode)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                update {
                    copy(
                        planContinuationId = null,
                        planContinuationErrorId = actionable.id,
                        planContinuationError = cause.message ?: "Plan continuation failed.",
                    )
                }
            }
        }
    }

    private fun terminateSession() {
        if (local.value.sessionTerminatingThreadId != null) return
        val detail = repository.focusedThread.value.value?.thread ?: return
        val session = detail.session ?: return
        if (session.threadId != detail.id || session.status !in ActiveSessionStatuses) return
        update {
            copy(
                sessionTerminatingThreadId = detail.id,
                sessionTerminalThreadId = null,
                sessionTerminationError = null,
            )
        }
        viewModelScope.launch {
            try {
                chat.terminateSession(detail.id)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                update {
                    copy(
                        sessionTerminatingThreadId = null,
                        sessionTerminationErrorThreadId = detail.id,
                        sessionTerminationError = cause.message ?: "Session termination failed.",
                    )
                }
            }
        }
    }

    private fun changeSnooze(action: SnoozeAction, snoozedUntil: String? = null) {
        if (local.value.snoozeActionThreadId != null) return
        val activeEnvironment = environments.activeEnvironment.value ?: return
        val config = repository.clientConfig.value.value ?: return
        if (config.environment.environmentId != activeEnvironment.id || !config.environment.capabilities.threadSnooze) return
        val focusedThreadId = repository.focusedThreadId.value ?: return
        val detail = repository.focusedThread.value.value?.thread ?: return
        if (detail.id != focusedThreadId) return
        val target = if (action == SnoozeAction.SNOOZE) {
            snoozedUntil?.takeIf { runCatching { Instant.parse(it) }.isSuccess } ?: return
        } else {
            null
        }
        if (action == SnoozeAction.SNOOZE && detail.snoozedUntil != null) return
        if (action == SnoozeAction.WAKE && detail.snoozedUntil == null) return
        update {
            copy(
                snoozeActionThreadId = detail.id,
                snoozeAction = action,
                snoozeTargetUntil = target,
                snoozeErrorThreadId = null,
                snoozeError = null,
            )
        }
        viewModelScope.launch {
            try {
                when (action) {
                    SnoozeAction.SNOOZE -> threads.snooze(detail.id, requireNotNull(target))
                    SnoozeAction.WAKE -> threads.wake(detail.id)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                update {
                    copy(
                        snoozeActionThreadId = null,
                        snoozeAction = null,
                        snoozeTargetUntil = null,
                        snoozeErrorThreadId = detail.id,
                        snoozeError = cause.message ?: "Thread snooze action failed.",
                    )
                }
            }
        }
    }

    private fun openThreadDeletion() {
        val detail = eligibleDeletionDetail() ?: return
        update {
            copy(
                deletionVisible = true,
                deletionThreadId = detail.id,
                deletionTitle = detail.title,
                deletionChecking = true,
                deletionError = null,
            )
        }
        viewModelScope.launch {
            val blockers = deletionBlockers(detail.id)
            update { copy(deletionChecking = false, deletionBlockers = blockers) }
        }
    }

    private fun acceptProject(intent: ProjectWorkflowIntent) {
        val workflow = projectWorkflow ?: return
        viewModelScope.launch { workflow.accept(intent) }
    }

    private fun acceptShared(intent: SharedTextWorkflowIntent) {
        val workflow = sharedTextWorkflow ?: return
        viewModelScope.launch { workflow.accept(intent) }
    }

    private fun acceptCache(intent: CacheAdministrationIntent) {
        val workflow = cacheAdministration ?: return
        viewModelScope.launch { workflow.accept(intent) }
    }

    private fun confirmThreadDeletion() {
        if (local.value.deletionInProgress || local.value.deletionChecking) return
        val threadId = local.value.deletionThreadId ?: return
        if (eligibleDeletionDetail()?.id != threadId) return
        update { copy(deletionChecking = true) }
        viewModelScope.launch {
            val blockers = deletionBlockers(threadId)
            if (blockers.isNotEmpty()) {
                update { copy(deletionChecking = false, deletionBlockers = blockers, deletionError = null) }
                return@launch
            }
            update { copy(deletionChecking = false, deletionInProgress = true, deletionError = null) }
            try {
                threads.delete(threadId)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                update {
                    copy(
                        deletionInProgress = false,
                        deletionError = cause.message ?: "Thread deletion failed.",
                    )
                }
            }
        }
    }

    private fun eligibleDeletionDetail(): de.chennemann.agentic.t3.contract.OrchestrationThreadDetail? {
        val active = environments.activeEnvironment.value ?: return null
        val config = repository.clientConfig.value.value ?: return null
        if (config.environment.environmentId != active.id || !config.environment.capabilities.threadDeletion) return null
        val focusedId = repository.focusedThreadId.value ?: return null
        return repository.focusedThread.value.value?.thread?.takeIf { it.id == focusedId }
    }

    private suspend fun deletionBlockers(threadId: String): List<String> {
        val detail = eligibleDeletionDetail()?.takeIf { it.id == threadId } ?: return listOf("Thread changed")
        val blockers = mutableListOf<String>()
        if (detail.session?.status in ActiveSessionStatuses || detail.latestTurn?.state in ActiveSessionStatuses) {
            blockers += "Active turn"
        }
        if (detail.activities.any { it.kind.endsWith(".requested") }) blockers += "Pending work"
        if (local.value.inFlightRequests.isNotEmpty() || local.value.sending) blockers += "Pending work"
        if (draft.value.isNotBlank()) blockers += "Unsent draft"
        val environmentId = environments.activeEnvironment.value?.id
        val pending = environmentId?.let { commandOutbox?.hasThreadWork(it, threadId, excludeDeletion = true) } == true
        if (pending) blockers += "Pending command"
        return blockers.distinct()
    }

    private fun submissionUnavailable(message: String) {
        update { copy(sending = false, commandError = message) }
    }

    private fun quickSwitchProject(projectId: String) {
        launchCommand {
            val shell = repository.shell.value.value ?: return@launchCommand
            if (shell.projects.none { it.id == projectId }) return@launchCommand
            val projectThreads = shell.threads
                .filter {
                    it.projectId == projectId &&
                        it.archivedAt == null &&
                        !it.isSettled()
                }
                .sortedWith(compareByDescending<de.chennemann.agentic.t3.contract.OrchestrationThreadShell> {
                    it.updatedAt
                }.thenByDescending { it.id })
            val focusedThreadId = repository.focusedThreadId.value
            val focusedIndex = projectThreads.indexOfFirst { it.id == focusedThreadId }
            val nextThreadId = when {
                repository.selectedProjectId.value != projectId -> projectThreads.firstOrNull()?.id
                focusedIndex < 0 -> projectThreads.firstOrNull()?.id
                projectThreads.isEmpty() -> null
                else -> projectThreads[(focusedIndex + 1) % projectThreads.size].id
            }
            if (nextThreadId == null) {
                threads.selectProject(projectId)
            } else {
                threads.selectThread(nextThreadId)
            }
        }
    }

    private fun selectThread(threadId: String) {
        launchCommand {
            threads.selectThread(threadId)
            update { copy(activePicker = null, pickerProjectId = null) }
        }
    }

    private fun renameThread() {
        val threadId = repository.focusedThreadId.value ?: return
        val title = local.value.renameDraft.trim()
        val selection = selectedModel() ?: return
        if (title.isEmpty()) return
        launchCommand {
            update { copy(renameSaving = true) }
            threads.rename(threadId, title, selection)
            update { copy(renameSaving = false, renameVisible = false) }
        }
    }

    private fun respondToApproval(event: ChatUiEvent.ApprovalDecisionSelected) {
        val threadId = repository.focusedThreadId.value ?: return
        update { copy(inFlightRequests = inFlightRequests + event.requestId) }
        launchCommand {
            chat.respondToApproval(
                threadId,
                event.requestId,
                event.decision.contractValue(),
            )
        }
    }

    private fun respondToUserInput(requestId: String) {
        val threadId = repository.focusedThreadId.value ?: return
        val activity = repository.focusedThread.value.value?.thread?.activities
            ?.firstOrNull {
                it.kind == "user-input.requested" &&
                    it.payload["requestId"]?.jsonPrimitive?.content == requestId
            } ?: return
        val answers = buildMap {
            activity.payload["questions"]?.jsonArray.orEmpty().forEach { element ->
                val question = element.jsonObject
                val questionId = question["id"]?.jsonPrimitive?.content ?: return@forEach
                val key = answerKey(requestId, questionId)
                val selected = local.value.optionAnswers[key].orEmpty()
                put(
                    questionId,
                    if (selected.size <= 1) {
                        JsonPrimitive(selected.firstOrNull() ?: local.value.textAnswers[key].orEmpty())
                    } else {
                        JsonArray(selected.sorted().map(::JsonPrimitive))
                    },
                )
            }
        }
        update { copy(inFlightRequests = inFlightRequests + requestId) }
        launchCommand { chat.respondToUserInput(threadId, requestId, JsonObject(answers)) }
    }

    private fun selectedModel(): ModelSelection? {
        val config = repository.clientConfig.value.value ?: return null
        val inheritedSelections = listOfNotNull(
            repository.focusedThread.value.value?.thread?.modelSelection,
            repository.shell.value.value?.projects
                ?.firstOrNull { it.id == repository.selectedProjectId.value }
                ?.defaultModelSelection,
        )
        val selectedId = local.value.providerModelId
            ?: inheritedSelections.firstOrNull()?.optionId()
        val pair = config.providers.flatMap { provider ->
            provider.models.map { model -> provider.instanceId to model.slug }
        }.firstOrNull { (instanceId, model) -> optionId(instanceId, model) == selectedId }
            ?: config.providers.firstNotNullOfOrNull { provider ->
                provider.models.firstOrNull()?.let { provider.instanceId to it.slug }
            }
        return pair?.let { selected ->
            val base = inheritedSelections.firstOrNull { selection ->
                selection.instanceId == selected.first && selection.model == selected.second
            } ?: ModelSelection(selected.first, selected.second)
            val modelId = optionId(selected.first, selected.second)
            val descriptors = providerOptionDescriptors(config, modelId)
            val resolved = resolveProviderOptions(
                modelId = modelId,
                descriptors = descriptors,
                inherited = base.options.orEmpty(),
                overrides = local.value.providerOptionValues,
            )
            base.copy(options = resolved.takeIf(List<de.chennemann.agentic.t3.contract.ProviderOptionSelection>::isNotEmpty))
        }
    }

    private fun selectedModelId(): String? {
        val detail = repository.focusedThread.value.value?.thread
        val project = repository.shell.value.value?.projects
            ?.firstOrNull { it.id == repository.selectedProjectId.value }
        return local.value.providerModelId
            ?: detail?.modelSelection?.optionId()
            ?: project?.defaultModelSelection?.optionId()
            ?: modelOptions(repository.clientConfig.value.value).firstOrNull()?.id
    }

    private fun launchCommand(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                update {
                    copy(
                        sending = false,
                        renameSaving = false,
                        commandError = it.message ?: "The command was not accepted.",
                    )
                }
            }
        }
    }

    private fun update(transform: LocalState.() -> LocalState) {
        local.value = local.value.transform()
    }

    private fun setDraft(value: String) {
        setDraft(displayedDraftKey, value)
    }

    private fun setDraft(
        key: DraftKey?,
        value: String,
        persistImmediately: Boolean = false,
    ) {
        if (key == null) {
            unthreadedDraft = value
        } else {
            draftCache[key] = value
            dirtyDraftKeys += key
            if (composerDrafts != null) {
                val write = DraftWrite(key, value)
                if (persistImmediately) {
                    cancelPendingDraftWrite(key)
                    draftWrites.trySend(write)
                } else {
                    scheduleDraftWrite(write)
                }
            }
        }
        if (key == displayedDraftKey) {
            mutableDraft.value = value
            if (local.value.commandError != null) {
                update { copy(commandError = null) }
            }
        }
    }

    private suspend fun selectDraft(
        key: DraftKey?,
        drafts: ComposerDraftRepository?,
    ) {
        if (draftSelectionInitialized && key == displayedDraftKey) return
        val previousKey = displayedDraftKey
        if (draftSelectionInitialized) flushPendingDraftWrite(displayedDraftKey)
        displayedDraftKey = key
        draftSelectionInitialized = true

        if (key == null) {
            showDraft(unthreadedDraft)
            return
        }
        if (draftCache.containsKey(key)) {
            showDraft(draftCache.getValue(key))
            return
        }
        if (previousKey == null && unthreadedDraft.isNotEmpty()) {
            val value = unthreadedDraft
            unthreadedDraft = ""
            setDraft(key, value, persistImmediately = true)
            return
        }

        showDraft("")
        val restored = drafts?.observe(key.environmentId, key.threadId)?.first().orEmpty()
        if (key == displayedDraftKey && key !in dirtyDraftKeys) {
            draftCache[key] = restored
            showDraft(restored)
        }
    }

    private fun showDraft(value: String) {
        mutableDraft.value = value
        if (local.value.commandError != null) {
            update { copy(commandError = null) }
        }
    }

    private fun scheduleDraftWrite(write: DraftWrite) {
        if (composerDrafts == null) return
        pendingDraftWriteJob?.cancel()
        pendingDraftWrite = write
        pendingDraftWriteJob = viewModelScope.launch {
            delay(DraftPersistenceDelayMillis)
            if (pendingDraftWrite == write) {
                pendingDraftWrite = null
                pendingDraftWriteJob = null
                draftWrites.send(write)
            }
        }
    }

    private fun flushPendingDraftWrite(key: DraftKey?) {
        val write = pendingDraftWrite?.takeIf { it.key == key } ?: return
        cancelPendingDraftWrite(key)
        draftWrites.trySend(write)
    }

    private fun cancelPendingDraftWrite(key: DraftKey?) {
        if (pendingDraftWrite?.key != key) return
        pendingDraftWriteJob?.cancel()
        pendingDraftWrite = null
        pendingDraftWriteJob = null
    }

    override fun onCleared() {
        voiceInput?.cancelRecording()
        super.onCleared()
    }

    private fun mapState(
        orchestration: OrchestrationBundle,
        environment: EnvironmentBundle,
        local: LocalState,
        favorites: List<FavoriteModelId>,
        timeline: List<ChatTimelineItemUi>,
    ): ChatUiState {
        val shell = orchestration.shell.value
        val detail = orchestration.thread.value?.thread?.takeIf { it.id == orchestration.threadId }
        val shellThread = shell?.threads?.firstOrNull { it.id == orchestration.threadId }
        val projectId = orchestration.projectId
            ?: detail?.projectId
            ?: shellThread?.projectId
            ?: shell?.projects?.firstOrNull()?.id
        val project = shell?.projects?.firstOrNull { it.id == projectId }
        val modelOptions = modelOptions(orchestration.config.value, favorites)
        val selectedModelId = local.providerModelId
            ?: detail?.modelSelection?.optionId()
            ?: project?.defaultModelSelection?.optionId()
            ?: modelOptions.firstOrNull()?.id
        val runtime = local.runtimeModeId ?: detail?.runtimeMode ?: DefaultRuntimeMode
        val inheritedOptions = listOfNotNull(
            detail?.modelSelection?.takeIf { it.optionId() == selectedModelId },
            project?.defaultModelSelection?.takeIf { it.optionId() == selectedModelId },
        ).firstOrNull()?.options.orEmpty()
        val descriptors = providerOptionDescriptors(orchestration.config.value, selectedModelId)
        val providerOptions = selectedModelId?.let {
            providerOptionsUi(
                modelId = it,
                descriptors = descriptors,
                inherited = inheritedOptions,
                overrides = local.providerOptionValues,
            )
        }.orEmpty()
        val pickerProjectId = local.pickerProjectId ?: projectId
        val pickerThreads = shell?.threads
            .orEmpty()
            .filter { it.projectId == pickerProjectId && (local.showArchived || it.archivedAt == null) }
            .sortedByDescending { it.updatedAt }

        return ChatUiState(
            title = detail?.title ?: shellThread?.title ?: "New thread",
            environmentLabel = environment.active?.label ?: "T3",
            projectLabel = project?.title,
            threadId = orchestration.threadId,
            timeline = timeline,
            connection = environment.connection.toUi(),
            composer = ComposerUiState(
                draft = "",
                selectedProviderModelId = selectedModelId,
                providerModels = modelOptions,
                providerOptions = providerOptions,
                selectedRuntimeModeId = runtime,
                runtimeModes = RuntimeModes,
                slashCommands = slashCommands(orchestration.config.value, selectedModelId),
                quickSwitchProjects = quickSwitchProjects(
                    shell = shell,
                    selectedProjectId = projectId,
                    unreadThreadIds = local.unreadThreadIds,
                ),
                sending = local.sending,
                errorMessage = local.commandError,
                enabled = environment.active != null && shell != null && project != null,
            ),
            environmentPicker = EnvironmentPickerUiState(
                environments = environment.all.map {
                    EnvironmentPickerItemUi(
                        id = it.id,
                        label = it.label,
                        supportingText = it.baseUrl,
                        connection = if (it.active) {
                            environment.connection.toIndicator()
                        } else {
                            EnvironmentConnectionIndicatorUi.OFFLINE
                        },
                    )
                },
                selectedEnvironmentId = environment.active?.id,
            ),
            threadPicker = ThreadPickerUiState(
                projects = shell?.projects.orEmpty()
                    .sortedWith(
                        compareBy<de.chennemann.agentic.t3.contract.OrchestrationProject, String>(
                            String.CASE_INSENSITIVE_ORDER,
                        ) {
                            it.title
                        }.thenBy { it.id },
                    )
                    .map {
                        ProjectPickerItemUi(it.id, it.title, it.workspaceRoot)
                    },
                selectedProjectId = pickerProjectId,
                threads = pickerThreads.filterNot { it.isSettled() }.map {
                    ThreadPickerItemUi(
                        id = it.id,
                        title = it.title,
                        supportingText = it.snoozedUntil?.let { until -> "Snoozed until $until" } ?: it.updatedAt,
                        archived = it.archivedAt != null,
                        active = it.session?.status in ActiveSessionStatuses,
                        snoozedUntil = it.snoozedUntil,
                    )
                },
                settledThreads = pickerThreads.filter { it.isSettled() }.map {
                    ThreadPickerItemUi(
                        id = it.id,
                        title = it.title,
                        supportingText = it.updatedAt,
                        archived = it.archivedAt != null,
                        active = it.session?.status in ActiveSessionStatuses,
                        snoozedUntil = it.snoozedUntil,
                    )
                },
                selectedThreadId = orchestration.threadId,
                showArchived = local.showArchived,
                showSettled = local.showSettled,
                loading = orchestration.threadId != null && detail?.id != orchestration.threadId,
            ),
            latestTurnChanges = detail?.latestTurnChanges() ?: LatestTurnChangesUiState(),
            activePicker = local.activePicker,
            isTurnRunning = detail?.session?.status in ActiveSessionStatuses &&
                detail?.session?.activeTurnId != null,
            canRenameThread = detail != null,
            canArchiveThread = detail != null && detail.archivedAt == null,
            isThreadArchived = detail?.archivedAt != null,
            canDeleteThread = detail != null &&
                orchestration.config.value?.environment?.capabilities?.threadDeletion == true &&
                orchestration.config.value.environment.environmentId == environment.active?.id,
            renameDialog = if (local.renameVisible) {
                RenameThreadUi(local.renameDraft, local.renameSaving)
            } else {
                null
            },
            environmentRemoval = local.environmentRemovalId?.let { environmentId ->
                EnvironmentRemovalUi(
                    environmentId = environmentId,
                    label = local.environmentRemovalLabel.orEmpty(),
                    removing = local.environmentRemovalSaving,
                    errorMessage = local.environmentRemovalError,
                )
            },
            projectCreation = null,
            projectRename = null,
            projectRemoval = null,
            sessionTermination = SessionTerminationUi(
                canTerminate = detail?.session?.status in ActiveSessionStatuses &&
                    detail?.session?.threadId == detail?.id &&
                    local.sessionTerminatingThreadId == null,
                terminating = local.sessionTerminatingThreadId == detail?.id,
                terminal = local.sessionTerminalThreadId == detail?.id &&
                    detail?.session?.status !in ActiveSessionStatuses,
                errorMessage = local.sessionTerminationError.takeIf {
                    local.sessionTerminationErrorThreadId == detail?.id
                },
            ),
            threadSnooze = ThreadSnoozeUi(
                supported = orchestration.config.value?.environment?.capabilities?.threadSnooze == true &&
                    orchestration.config.value.environment.environmentId == environment.active?.id,
                isSnoozed = detail?.snoozedUntil != null,
                snoozedAt = detail?.snoozedAt,
                snoozedUntil = detail?.snoozedUntil,
                wakeReason = detail?.lastWakeReason,
                canSnooze = orchestration.config.value?.environment?.capabilities?.threadSnooze == true &&
                    orchestration.config.value.environment.environmentId == environment.active?.id &&
                    detail != null && detail.snoozedUntil == null && local.snoozeActionThreadId == null,
                canWake = orchestration.config.value?.environment?.capabilities?.threadSnooze == true &&
                    orchestration.config.value.environment.environmentId == environment.active?.id &&
                    detail?.snoozedUntil != null && local.snoozeActionThreadId == null,
                actionInProgress = local.snoozeActionThreadId == detail?.id,
                errorMessage = local.snoozeError.takeIf { local.snoozeErrorThreadId == detail?.id },
            ),
            threadDeletion = local.deletionThreadId?.takeIf { local.deletionVisible }?.let { threadId ->
                ThreadDeletionUi(
                    threadId = threadId,
                    title = local.deletionTitle,
                    blockers = local.deletionBlockers,
                    checking = local.deletionChecking,
                    deleting = local.deletionInProgress,
                    errorMessage = local.deletionError,
                )
            },
        )
    }

    private fun de.chennemann.agentic.t3.contract.OrchestrationThreadDetail.latestTurnChanges():
        LatestTurnChangesUiState {
        val turnId = latestTurn?.turnId ?: return LatestTurnChangesUiState()
        val checkpoint = checkpoints
            .filterIsInstance<JsonObject>()
            .lastOrNull { it.string("turnId") == turnId }
            ?.takeIf { it.string("status") == "ready" }
            ?: return LatestTurnChangesUiState(turnId = turnId)
        val files = (checkpoint["files"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                val file = element as? JsonObject ?: return@mapNotNull null
                val path = file.string("path")?.trim()?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                ChangedFileUi(
                    path = path,
                    kind = file.string("kind").orEmpty(),
                    additions = file["additions"]?.jsonPrimitive?.intOrNull ?: 0,
                    deletions = file["deletions"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
            .distinctBy(ChangedFileUi::path)
            .sortedWith(
                compareBy<ChangedFileUi, String>(String.CASE_INSENSITIVE_ORDER) { it.path },
            )
        return LatestTurnChangesUiState(turnId = turnId, files = files)
    }

    private fun timeline(
        detail: de.chennemann.agentic.t3.contract.OrchestrationThreadDetail,
        local: LocalState,
    ): List<ChatTimelineItemUi> {
        val messages = detail.messages.map {
            TimedItem(
                at = it.createdAt,
                item = ChatTimelineItemUi.Message(
                    ChatMessageUi(
                        id = it.id,
                        author = when (it.role) {
                            "user" -> ChatMessageAuthorUi.USER
                            "assistant" -> ChatMessageAuthorUi.ASSISTANT
                            else -> ChatMessageAuthorUi.SYSTEM
                        },
                        content = it.text,
                        isStreaming = it.streaming,
                    ),
                ),
                turnId = it.turnId,
                turnPhase = if (it.role == "user") TurnPhaseUser else TurnPhaseAssistant,
            )
        }
        val oldestApproval = detail.activities
            .filter { it.kind == "approval.requested" }
            .minWithOrNull(activityOrder)
            ?.id
        val oldestUserInput = detail.activities
            .filter { it.kind == "user-input.requested" }
            .minWithOrNull(activityOrder)
            ?.id
        val finishedTurnId = detail.latestTurn
            ?.takeIf { it.state !in ActiveSessionStatuses }
            ?.turnId
        val activities = detail.activities.filter {
            if (
                it.kind == "task.started" ||
                it.kind == "context-window.updated" ||
                it.summary == "Checkpoint captured"
            ) {
                return@filter false
            }
            when (it.kind) {
                "approval.requested" -> it.id == oldestApproval
                "user-input.requested" -> it.id == oldestUserInput
                else -> true
            }
        }.map {
            val activity = it.toUi(local).completeIfTurnFinished(
                finished = finishedTurnId != null && it.turnId == finishedTurnId,
            )
            TimedItem(
                at = it.createdAt,
                item = ChatTimelineItemUi.Activity(activity),
                sequence = it.sequence ?: Long.MAX_VALUE,
                lifecycleRank = activityLifecycleRank(it.kind),
                turnId = it.turnId,
                turnPhase = TurnPhaseActivity,
            )
        }
        val latestActionablePlan = detail.proposedPlans
            .filter { it.implementationThreadId == null && it.implementedAt == null }
            .maxWithOrNull(compareBy<de.chennemann.agentic.t3.contract.ProposedPlan> { it.updatedAt }.thenBy { it.id })
        val plans = detail.proposedPlans.map { plan ->
            val continuing = local.planContinuationId == plan.id
            TimedItem(
                at = plan.createdAt,
                item = ChatTimelineItemUi.Activity(
                    ChatActivityUi.ProposedPlan(
                        id = plan.id,
                        summary = "Proposed plan",
                        planMarkdown = plan.planMarkdown,
                        canContinue = plan.id == latestActionablePlan?.id &&
                            detail.session?.status !in ActiveSessionStatuses &&
                            !continuing,
                        continuing = continuing,
                        errorMessage = local.planContinuationError.takeIf {
                            local.planContinuationErrorId == plan.id
                        },
                    ),
                ),
                turnId = plan.turnId,
                turnPhase = TurnPhaseActivity,
            )
        }
        val items = messages + activities + plans
        val turnStartedAt = items
            .filter { it.turnId != null }
            .groupBy { requireNotNull(it.turnId) }
            .mapValues { (_, turnItems) -> turnItems.minOf { it.at } }
        val sorted = items
            .sortedWith(
                compareBy<TimedItem> { it.turnId?.let(turnStartedAt::get) ?: it.at }
                    .thenBy { it.turnId.orEmpty() }
                    .thenBy { it.at }
                    .thenBy { it.turnPhase }
                    .thenBy { it.sequence }
                    .thenBy { it.lifecycleRank }
                    .thenBy { it.item.id },
            )
            .map { it.item }
        return groupToolActivities(sorted)
    }

    private fun OrchestrationActivity.toUi(local: LocalState): ChatActivityUi {
        val requestId = payload.string("requestId") ?: id
        val inFlight = requestId in local.inFlightRequests
        return when (kind) {
            "approval.requested" -> ChatActivityUi.Approval(
                id,
                PendingApprovalUi(
                    requestId = requestId,
                    title = summary,
                    description = payload.string("command"),
                    decisions = (payload["decisions"] as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonPrimitive)?.content?.let(::approvalDecisionFromContract)
                    },
                    responseInFlight = inFlight,
                ),
            )

            "user-input.requested" -> ChatActivityUi.UserInput(
                id,
                PendingUserInputUi(
                    requestId = requestId,
                    title = summary,
                    description = payload.string("description"),
                    questions = (payload["questions"] as? JsonArray).orEmpty().mapNotNull { element ->
                        val question = element as? JsonObject ?: return@mapNotNull null
                        val questionId = question.string("id") ?: return@mapNotNull null
                        val key = answerKey(requestId, questionId)
                        UserInputQuestionUi(
                            id = questionId,
                            label = question.string("prompt")
                                ?: question.string("question")
                                ?: questionId,
                            description = question.string("description"),
                            required = question.string("required")?.toBooleanStrictOrNull() ?: true,
                            allowsMultiple = question.string("multiple") == "true",
                            options = (question["options"] as? JsonArray)
                                .orEmpty()
                                .mapNotNull(JsonElement::toUserInputOptionUi),
                            textAnswer = local.textAnswers[key].orEmpty(),
                            selectedOptionIds = local.optionAnswers[key].orEmpty(),
                        )
                    },
                    responseInFlight = inFlight,
                ),
            )

            else -> when {
                summary.equals("Plan updated", ignoreCase = true) ->
                    payload.toToolUi(this).copy(status = ActivityStatusUi.COMPLETED)

                tone == "tool" -> payload.toToolUi(this)

                tone == "error" -> ChatActivityUi.Error(id, summary, payload.pretty())
                tone == "info" && kind.startsWith("status.") ->
                    ChatActivityUi.Information(id, summary, payload.pretty())

                tone == "info" -> ChatActivityUi.Unknown(id, summary, payload.pretty(), kind)
                else -> ChatActivityUi.Unknown(id, summary, payload.pretty(), kind)
            }
        }
    }

    private fun ChatActivityUi.completeIfTurnFinished(finished: Boolean): ChatActivityUi =
        if (
            finished &&
            this is ChatActivityUi.Tool &&
            status in setOf(ActivityStatusUi.PENDING, ActivityStatusUi.RUNNING)
        ) {
            copy(status = ActivityStatusUi.COMPLETED)
        } else {
            this
        }

    private fun JsonObject.toToolUi(activity: OrchestrationActivity): ChatActivityUi.Tool {
        val command = toolCommand()
        val output = string("detail")?.stripTrailingExitCode()
        val changedFiles = changedFiles()
        val title = (string("title") ?: activity.summary)
            .replace(Regex("\\s+(started|complete|completed)$", RegexOption.IGNORE_CASE), "")
            .replaceFirstChar(Char::uppercase)
        val preview = command ?: output ?: changedFiles.firstOrNull() ?: string("toolName")
        val fullDetail = listOfNotNull(
            command,
            output?.takeUnless { it == command },
            changedFiles.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        ).distinct().joinToString("\n\n").ifBlank {
            pretty().takeUnless { it == "{}" }
        }
        val lifecycleStatus = string("status")
        val status = when {
            lifecycleStatus == "failed" || lifecycleStatus == "declined" -> ActivityStatusUi.FAILED
            lifecycleStatus == "completed" || activity.kind.endsWith(".completed") -> ActivityStatusUi.COMPLETED
            lifecycleStatus == "inProgress" ||
                activity.kind.endsWith(".started") ||
                activity.kind.endsWith(".updated") -> ActivityStatusUi.RUNNING
            lifecycleStatus == "stopped" -> ActivityStatusUi.FAILED
            else -> ActivityStatusUi.PENDING
        }
        val item = (this["data"] as? JsonObject)?.get("item") as? JsonObject
        val lifecycleId = item?.string("id")
            ?: item?.string("callId")
            ?: string("callId")
            ?: string("toolCallId")
        val lifecycleFallbackKey = listOf(
            string("itemType").orEmpty(),
            title,
            command.orEmpty(),
        ).joinToString("\u001f")
        return ChatActivityUi.Tool(
            id = activity.id,
            summary = title,
            subtitle = preview?.replace(Regex("\\s+"), " ")?.trim(),
            status = status,
            detail = fullDetail,
            lifecycleKey = lifecycleId,
            lifecycleFallbackKey = lifecycleFallbackKey,
        )
    }

    private data class MappedInput(
        val orchestration: OrchestrationBundle,
        val environment: EnvironmentBundle,
        val local: LocalState,
        val favorites: List<FavoriteModelId>,
    ) {
        fun timelineInput() = TimelineInput(
            detail = orchestration.thread.value?.thread?.takeIf { it.id == orchestration.threadId },
            planContinuationId = local.planContinuationId,
            planContinuationErrorId = local.planContinuationErrorId,
            planContinuationError = local.planContinuationError,
            inFlightRequests = local.inFlightRequests,
            textAnswers = local.textAnswers,
            optionAnswers = local.optionAnswers,
        )
    }

    private data class TimelineInput(
        val detail: de.chennemann.agentic.t3.contract.OrchestrationThreadDetail?,
        val planContinuationId: String?,
        val planContinuationErrorId: String?,
        val planContinuationError: String?,
        val inFlightRequests: Set<String>,
        val textAnswers: Map<String, String>,
        val optionAnswers: Map<String, Set<String>>,
    )

    private data class WorkflowPresentation(
        val project: ProjectWorkflowState,
        val shared: SharedTextWorkflowState,
        val cache: CacheAdministrationState,
        val shortcutError: String?,
    )

    private data class LocalState(
        val activePicker: ChatPickerUi? = null,
        val showArchived: Boolean = false,
        val showSettled: Boolean = false,
        val providerModelId: String? = null,
        val providerOptionValues: Map<String, JsonPrimitive> = emptyMap(),
        val runtimeModeId: String? = null,
        val sending: Boolean = false,
        val commandError: String? = null,
        val renameVisible: Boolean = false,
        val renameDraft: String = "",
        val renameSaving: Boolean = false,
        val inFlightRequests: Set<String> = emptySet(),
        val textAnswers: Map<String, String> = emptyMap(),
        val optionAnswers: Map<String, Set<String>> = emptyMap(),
        val pickerProjectId: String? = null,
        val unreadThreadIds: Set<String> = emptySet(),
        val voiceInputStatus: VoiceInputStatusUi = VoiceInputStatusUi.IDLE,
        val groqSettingsVisible: Boolean = false,
        val groqSettingsSaving: Boolean = false,
        val groqSettingsError: String? = null,
        val environmentRemovalId: String? = null,
        val environmentRemovalLabel: String? = null,
        val environmentRemovalSaving: Boolean = false,
        val environmentRemovalError: String? = null,
        val planContinuationId: String? = null,
        val planContinuationErrorId: String? = null,
        val planContinuationError: String? = null,
        val sessionTerminatingThreadId: String? = null,
        val sessionTerminalThreadId: String? = null,
        val sessionTerminationErrorThreadId: String? = null,
        val sessionTerminationError: String? = null,
        val snoozeActionThreadId: String? = null,
        val snoozeAction: SnoozeAction? = null,
        val snoozeTargetUntil: String? = null,
        val snoozeErrorThreadId: String? = null,
        val snoozeError: String? = null,
        val deletionVisible: Boolean = false,
        val deletionThreadId: String? = null,
        val deletionTitle: String = "",
        val deletionBlockers: List<String> = emptyList(),
        val deletionChecking: Boolean = false,
        val deletionInProgress: Boolean = false,
        val deletionError: String? = null,
    ) {
        fun structural(): LocalState = copy(
            sending = false,
            commandError = null,
        )
    }

    private data class DraftKey(
        val environmentId: String,
        val threadId: String,
    )

    private data class DraftWrite(
        val key: DraftKey,
        val value: String,
    )

}

private enum class SnoozeAction { SNOOZE, WAKE }

private data class OrchestrationBundle(
    val config: ProjectionState<ServerConfig>,
    val shell: ProjectionState<OrchestrationShellSnapshot>,
    val thread: ProjectionState<OrchestrationThreadDetailSnapshot>,
    val projectId: String?,
    val threadId: String?,
)

private data class EnvironmentBundle(
    val all: List<de.chennemann.agentic.domain.environment.SavedEnvironment>,
    val active: de.chennemann.agentic.domain.environment.SavedEnvironment?,
    val connection: ConnectionState,
)

private data class TimedItem(
    val at: String,
    val item: ChatTimelineItemUi,
    val sequence: Long = Long.MIN_VALUE,
    val lifecycleRank: Int = 0,
    val turnId: String? = null,
    val turnPhase: Int = TurnPhaseActivity,
)

private fun activityLifecycleRank(kind: String): Int = when {
    kind.endsWith(".started") -> 0
    kind.endsWith(".progress") || kind.endsWith(".updated") -> 1
    kind.endsWith(".completed") || kind.endsWith(".resolved") -> 2
    else -> 1
}

private fun groupToolActivities(items: List<ChatTimelineItemUi>): List<ChatTimelineItemUi> {
    val grouped = mutableListOf<ChatTimelineItemUi>()
    items.forEach { item ->
        val tool = (item as? ChatTimelineItemUi.Activity)?.value as? ChatActivityUi.Tool
        if (tool == null) {
            grouped += item
            return@forEach
        }
        val previous = grouped.lastOrNull() as? ChatTimelineItemUi.ToolGroup
        if (previous == null) {
            grouped += ChatTimelineItemUi.ToolGroup(
                id = "tool-group:${tool.id}",
                activities = listOf(tool),
            )
            return@forEach
        }
        val replaceIndex = previous.activities.indexOfLast {
            (
                it.lifecycleKey != null && it.lifecycleKey == tool.lifecycleKey ||
                    (it.lifecycleKey == null || tool.lifecycleKey == null) &&
                    it.lifecycleFallbackKey == tool.lifecycleFallbackKey
            ) &&
                it.status != ActivityStatusUi.COMPLETED &&
                it.status != ActivityStatusUi.FAILED
        }
        val nextActivities = if (replaceIndex >= 0) {
            previous.activities.toMutableList().apply { this[replaceIndex] = tool }
        } else {
            previous.activities + tool
        }
        grouped[grouped.lastIndex] = previous.copy(activities = nextActivities)
    }
    return grouped
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement.toUserInputOptionUi(): UserInputOptionUi? = when (this) {
    is JsonPrimitive -> content.trim().takeIf(String::isNotEmpty)?.let {
        UserInputOptionUi(id = it, label = it)
    }

    is JsonObject -> {
        val id = string("id") ?: string("value") ?: string("label") ?: return null
        UserInputOptionUi(
            id = id,
            label = string("label") ?: string("value") ?: id,
            description = string("description"),
        )
    }

    else -> null
}

private fun JsonObject.toolCommand(): String? {
    val data = this["data"] as? JsonObject
    val item = data?.get("item") as? JsonObject
    val input = item?.get("input") as? JsonObject
    val result = item?.get("result") as? JsonObject
    val candidates = listOfNotNull(
        item?.get("command"),
        input?.get("command"),
        result?.get("command"),
        data?.get("command"),
    )
    return candidates.firstNotNullOfOrNull(::commandText)?.unwrapShellCommand()
        ?: if (string("itemType") == "command_execution") {
            string("detail")?.stripTrailingExitCode()?.unwrapShellCommand()
        } else {
            null
        }
}

private fun commandText(value: kotlinx.serialization.json.JsonElement): String? = when (value) {
    is JsonPrimitive -> value.content.trim().takeIf { it.isNotEmpty() }
    is JsonArray -> value.mapNotNull {
        (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
    }.takeIf(List<String>::isNotEmpty)?.joinToString(" ")

    else -> null
}

private fun String.unwrapShellCommand(): String {
    val value = trim()
    val wrapper = Regex(
        """^(?:"?[^"]*(?:pwsh|powershell)(?:\.exe)?"?\s+-command|"?[^"]*(?:bash|zsh|sh)"?\s+-(?:l)?c)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    val command = wrapper.matchEntire(value)?.groupValues?.getOrNull(1)?.trim() ?: return value
    return command.removeSurrounding("\"").removeSurrounding("'").trim()
}

private fun String.stripTrailingExitCode(): String? {
    val output = replace(
        Regex("""\s*<exited with exit code \d+>\s*$""", RegexOption.IGNORE_CASE),
        "",
    ).trim()
    return output.takeIf { it.isNotEmpty() }
}

private fun JsonObject.changedFiles(): List<String> {
    val files = linkedSetOf<String>()
    fun collect(value: kotlinx.serialization.json.JsonElement?, depth: Int) {
        if (value == null || depth > 4 || files.size >= 12) return
        when (value) {
            is JsonArray -> value.forEach { collect(it, depth + 1) }
            is JsonObject -> {
                listOf("path", "filePath", "relativePath", "filename", "newPath", "oldPath")
                    .mapNotNull(value::string)
                    .forEach(files::add)
                listOf(
                    "item",
                    "result",
                    "input",
                    "data",
                    "changes",
                    "files",
                    "edits",
                    "patch",
                    "patches",
                    "operations",
                ).forEach { collect(value[it], depth + 1) }
            }

            else -> Unit
        }
    }
    collect(this["data"], 0)
    return files.toList()
}

private val activityOrder = compareBy<OrchestrationActivity>(
    { it.sequence ?: Long.MAX_VALUE },
    { it.createdAt },
    { it.id },
)

private fun quickSwitchProjects(
    shell: OrchestrationShellSnapshot?,
    selectedProjectId: String?,
    unreadThreadIds: Set<String>,
): List<ProjectQuickSwitchUi> {
    val snapshot = shell ?: return emptyList()
    val activeThreadsByProject = snapshot.threads
        .filter { it.archivedAt == null && !it.isSettled() && it.snoozedUntil == null }
        .groupBy { it.projectId }
    return snapshot.projects
        .mapNotNull { project ->
            val projectThreads = activeThreadsByProject[project.id].orEmpty()
            if (projectThreads.isEmpty()) return@mapNotNull null
            ActiveProject(
                project = project,
                threads = projectThreads,
                lastActiveAt = projectThreads.maxOf { it.updatedAt },
            )
        }
        .sortedWith(
            compareByDescending<ActiveProject> { it.lastActiveAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.project.title }
                .thenBy { it.project.id },
        )
        .take(MaxQuickSwitchProjects)
        .sortedWith(
            compareBy<ActiveProject, String>(String.CASE_INSENSITIVE_ORDER) { it.project.title }
                .thenBy { it.project.id },
        )
        .map { activeProject ->
            val project = activeProject.project
            val projectThreads = activeProject.threads
            ProjectQuickSwitchUi(
                id = project.id,
                label = project.title
                    .firstOrNull(Char::isLetterOrDigit)
                    ?.uppercaseChar()
                    ?.toString()
                    ?: "?",
                title = project.title,
                active = project.id == selectedProjectId,
                processing = projectThreads.any { it.session?.status in ActiveSessionStatuses },
                unreadCount = projectThreads.count { it.id in unreadThreadIds },
                attentionCount = projectThreads.count {
                    it.hasPendingApprovals ||
                        it.hasPendingUserInput ||
                        it.hasActionableProposedPlan
                },
            )
        }
}

private data class ActiveProject(
    val project: de.chennemann.agentic.t3.contract.OrchestrationProject,
    val threads: List<de.chennemann.agentic.t3.contract.OrchestrationThreadShell>,
    val lastActiveAt: String,
)

private fun de.chennemann.agentic.t3.contract.OrchestrationThreadShell.isSettled(): Boolean =
    settledOverride == "settled" || (settledOverride != "active" && settledAt != null)

private fun modelOptions(
    config: ServerConfig?,
    favorites: List<FavoriteModelId> = emptyList(),
): List<ProviderModelOptionUi> = config
    ?.providers
    .orEmpty()
    .filter { it.enabled && it.installed }
    .flatMap { provider ->
        provider.models.map { model ->
            ProviderModelOptionUi(
                id = optionId(provider.instanceId, model.slug),
                providerInstanceId = provider.instanceId,
                providerLabel = provider.displayName,
                modelLabel = model.name,
                supportingText = model.slug,
                favorite = FavoriteModelId(provider.instanceId, model.slug) in favorites,
                favoriteOrder = favorites.indexOf(
                    FavoriteModelId(provider.instanceId, model.slug),
                ).takeIf { it >= 0 },
            )
        }
    }

private sealed interface ProviderOptionDescriptor {
    val id: String
    val label: String
    val description: String?
    val currentValue: JsonPrimitive?

    data class Select(
        override val id: String,
        override val label: String,
        override val description: String?,
        override val currentValue: JsonPrimitive?,
        val values: List<Value>,
    ) : ProviderOptionDescriptor {
        data class Value(
            val id: String,
            val label: String,
            val description: String?,
            val isDefault: Boolean,
        )
    }

    data class Toggle(
        override val id: String,
        override val label: String,
        override val description: String?,
        override val currentValue: JsonPrimitive?,
    ) : ProviderOptionDescriptor
}

private fun providerOptionDescriptors(
    config: ServerConfig?,
    modelId: String?,
): List<ProviderOptionDescriptor> {
    if (config == null || modelId == null) return emptyList()
    val providerId = modelId.substringBefore('/')
    val modelSlug = modelId.substringAfter('/', missingDelimiterValue = "")
    val model = config.providers
        .firstOrNull { it.instanceId == providerId }
        ?.models
        ?.firstOrNull { it.slug == modelSlug }
        ?: return emptyList()
    val descriptors = model.capabilities["optionDescriptors"] as? JsonArray ?: return emptyList()
    return descriptors.mapNotNull { element ->
        val descriptor = element as? JsonObject ?: return@mapNotNull null
        val id = descriptor.string("id") ?: return@mapNotNull null
        val label = descriptor.string("label") ?: return@mapNotNull null
        val description = descriptor.string("description")
        val current = descriptor["currentValue"] as? JsonPrimitive
        when (descriptor.string("type")) {
            "select" -> {
                val values = (descriptor["options"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { valueElement ->
                        val value = valueElement as? JsonObject ?: return@mapNotNull null
                        ProviderOptionDescriptor.Select.Value(
                            id = value.string("id") ?: return@mapNotNull null,
                            label = value.string("label") ?: return@mapNotNull null,
                            description = value.string("description"),
                            isDefault = (value["isDefault"] as? JsonPrimitive)?.content == "true",
                        )
                    }
                values.takeIf(List<ProviderOptionDescriptor.Select.Value>::isNotEmpty)?.let {
                    ProviderOptionDescriptor.Select(id, label, description, current, it)
                }
            }

            "boolean" -> ProviderOptionDescriptor.Toggle(id, label, description, current)
            else -> null
        }
    }
}

private fun providerOptionsUi(
    modelId: String,
    descriptors: List<ProviderOptionDescriptor>,
    inherited: List<de.chennemann.agentic.t3.contract.ProviderOptionSelection>,
    overrides: Map<String, JsonPrimitive>,
): List<ProviderOptionUi> = descriptors.map { descriptor ->
    val selected = resolvedProviderOption(modelId, descriptor, inherited, overrides)
    when (descriptor) {
        is ProviderOptionDescriptor.Select -> ProviderOptionUi.Select(
            id = descriptor.id,
            label = descriptor.label,
            description = descriptor.description,
            values = descriptor.values.map {
                ProviderOptionValueUi(it.id, it.label, it.description)
            },
            selectedValueId = selected.content,
        )

        is ProviderOptionDescriptor.Toggle -> ProviderOptionUi.Toggle(
            id = descriptor.id,
            label = descriptor.label,
            description = descriptor.description,
            selected = selected.content == "true",
        )
    }
}

private fun resolveProviderOptions(
    modelId: String,
    descriptors: List<ProviderOptionDescriptor>,
    inherited: List<de.chennemann.agentic.t3.contract.ProviderOptionSelection>,
    overrides: Map<String, JsonPrimitive>,
): List<de.chennemann.agentic.t3.contract.ProviderOptionSelection> {
    val resolved = inherited.associateByTo(linkedMapOf()) { it.id }
    descriptors.forEach { descriptor ->
        resolved[descriptor.id] = de.chennemann.agentic.t3.contract.ProviderOptionSelection(
            descriptor.id,
            resolvedProviderOption(modelId, descriptor, inherited, overrides),
        )
    }
    return resolved.values.toList()
}

private fun resolvedProviderOption(
    modelId: String,
    descriptor: ProviderOptionDescriptor,
    inherited: List<de.chennemann.agentic.t3.contract.ProviderOptionSelection>,
    overrides: Map<String, JsonPrimitive>,
): JsonPrimitive {
    val candidate = overrides[optionValueKey(modelId, descriptor.id)]
        ?: inherited.lastOrNull { it.id == descriptor.id }?.value
        ?: descriptor.currentValue
    return when (descriptor) {
        is ProviderOptionDescriptor.Select -> {
            candidate
                ?.takeIf { value -> descriptor.values.any { it.id == value.content } }
                ?: JsonPrimitive(
                    descriptor.values.firstOrNull { it.isDefault }?.id
                        ?: descriptor.values.first().id,
                )
        }

        is ProviderOptionDescriptor.Toggle ->
            candidate?.takeIf { it.content == "true" || it.content == "false" } ?: JsonPrimitive(false)
    }
}

private fun optionValueKey(
    modelId: String,
    optionId: String,
): String = "$modelId\u0000$optionId"

private fun String.toFavoriteModelId(): FavoriteModelId? {
    val separator = indexOf('/')
    if (separator <= 0 || separator == lastIndex) return null
    return FavoriteModelId(substring(0, separator), substring(separator + 1))
}

private fun slashCommands(
    config: ServerConfig?,
    selectedModelId: String?,
): List<SlashCommandUi> {
    val instanceId = selectedModelId?.substringBefore('/')
    return config?.providers
        ?.firstOrNull { it.instanceId == instanceId }
        ?.slashCommands
        .orEmpty()
        .map { SlashCommandUi(it.name, it.description) }
}

private val PrettyT3Json = Json(T3Json) {
    prettyPrint = true
}

private fun JsonObject.pretty(): String = PrettyT3Json.encodeToString(this)

private fun ModelSelection.optionId(): String = optionId(instanceId, model)

private fun optionId(
    instanceId: String,
    model: String,
): String = "$instanceId/$model"

private fun ApprovalDecisionUi.contractValue(): String = when (this) {
    ApprovalDecisionUi.ACCEPT -> "accept"
    ApprovalDecisionUi.ACCEPT_FOR_SESSION -> "acceptForSession"
    ApprovalDecisionUi.DECLINE -> "decline"
    ApprovalDecisionUi.CANCEL -> "cancel"
}

private fun approvalDecisionFromContract(value: String): ApprovalDecisionUi? = when (value) {
    "accept" -> ApprovalDecisionUi.ACCEPT
    "acceptForSession" -> ApprovalDecisionUi.ACCEPT_FOR_SESSION
    "decline" -> ApprovalDecisionUi.DECLINE
    "cancel" -> ApprovalDecisionUi.CANCEL
    else -> null
}

private fun answerKey(
    requestId: String,
    questionId: String,
): String = "$requestId\u0000$questionId"

private fun ConnectionState.toUi(): ChatConnectionUi = when (this) {
    ConnectionState.NoEnvironment -> ChatConnectionUi.Failed("No environment selected.", false)
    ConnectionState.Cached -> ChatConnectionUi.Cached()
    ConnectionState.Connecting -> ChatConnectionUi.Connecting()
    ConnectionState.Synchronizing -> ChatConnectionUi.Synchronizing()
    ConnectionState.Live -> ChatConnectionUi.Live
    is ConnectionState.Backoff -> ChatConnectionUi.Reconnecting(message)
    is ConnectionState.BlockedAuthentication -> ChatConnectionUi.Blocked(message)
    is ConnectionState.UnsupportedProtocol -> ChatConnectionUi.Unsupported(message)
    is ConnectionState.Error -> ChatConnectionUi.Failed(message)
}

private fun ConnectionState.toIndicator(): EnvironmentConnectionIndicatorUi = when (this) {
    ConnectionState.Live -> EnvironmentConnectionIndicatorUi.LIVE
    ConnectionState.Cached -> EnvironmentConnectionIndicatorUi.CACHED
    ConnectionState.Connecting,
    ConnectionState.Synchronizing,
    is ConnectionState.Backoff,
    -> EnvironmentConnectionIndicatorUi.CONNECTING

    is ConnectionState.BlockedAuthentication,
    is ConnectionState.UnsupportedProtocol,
    is ConnectionState.Error,
    -> EnvironmentConnectionIndicatorUi.BLOCKED

    ConnectionState.NoEnvironment -> EnvironmentConnectionIndicatorUi.OFFLINE
}

private val RuntimeModes = listOf(
    RuntimeModeOptionUi(
        id = "approval-required",
        label = "Supervised",
        description = "Ask before changes.",
    ),
    RuntimeModeOptionUi(
        id = "auto-accept-edits",
        label = "Auto-accept edits",
        description = "Accept routine file edits automatically.",
    ),
    RuntimeModeOptionUi(
        id = "auto",
        label = "Auto",
        description = "An AI reviewer approves routine actions; risky ones still ask.",
    ),
    RuntimeModeOptionUi(
        id = "full-access",
        label = "Full access",
        description = "Allow all supported actions without approval.",
    ),
)
private val ActiveSessionStatuses = setOf("starting", "running")
private const val MaxQuickSwitchProjects = 5
private const val NewThreadDraftPrefix = "new-project:"
private const val DefaultRuntimeMode = "full-access"
private const val DraftPersistenceDelayMillis = 300L
private const val TurnPhaseUser = 0
private const val TurnPhaseAssistant = 1
private const val TurnPhaseActivity = 2
