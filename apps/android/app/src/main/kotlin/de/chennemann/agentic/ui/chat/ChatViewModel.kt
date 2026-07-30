package de.chennemann.agentic.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.connection.ConnectionState
import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentSelector
import de.chennemann.agentic.domain.orchestration.ChatActions
import de.chennemann.agentic.domain.orchestration.ChatService
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.ThreadService
import de.chennemann.agentic.domain.orchestration.ThreadActions
import de.chennemann.agentic.domain.preferences.FavoriteModelId
import de.chennemann.agentic.domain.preferences.ModelFavoriteRepository
import de.chennemann.agentic.domain.voice.GroqApiKeyStore
import de.chennemann.agentic.domain.voice.VoiceInputService
import de.chennemann.agentic.t3.contract.EnvironmentClientConfig
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.OrchestrationActivity
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.PortableJson
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val modelFavorites: ModelFavoriteRepository? = null,
    private val groqApiKeys: GroqApiKeyStore? = null,
    private val voiceInput: VoiceInputService? = null,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())
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

    init {
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

    private val mappedState = combine(
        orchestration,
        environment,
        local.map(LocalState::structural).distinctUntilChanged(),
        favorites,
    ) { orchestration, environment, local, favorites ->
        MappedInput(orchestration, environment, local, favorites)
    }.conflate().map { input ->
        mapState(input.orchestration, input.environment, input.local, input.favorites)
    }.flowOn(mappingDispatcher)

    val state = combine(
        mappedState,
        local,
        groqApiKeys?.configured ?: flowOf(false),
    ) { mapped, local, groqConfigured ->
        mapped.copy(
            composer = mapped.composer.copy(
                draft = local.draft,
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
            is ChatUiEvent.DraftChanged -> update {
                copy(draft = event.value, commandError = null)
            }
            ChatUiEvent.MessageSubmitted -> submitMessage()
            ChatUiEvent.VoiceInputPressed -> handleVoiceInput()
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

            is ChatUiEvent.InteractionModeSelected -> {
                update { copy(interactionMode = event.mode) }
                repository.focusedThreadId.value?.let { threadId ->
                    launchCommand { chat.setInteractionMode(threadId, event.mode.contractValue()) }
                }
            }

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

            is ChatUiEvent.SlashCommandSelected -> update {
                copy(draft = "/${event.name} ", activePicker = null)
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
                            update {
                                copy(
                                    draft = transcript,
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
        val prompt = local.value.draft.trim()
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
        val interactionMode = local.value.interactionMode.takeUnless {
            it == InteractionModeUi.DEFAULT && detail?.interactionMode == "plan"
        } ?: InteractionModeUi.PLAN
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
                interactionMode = interactionMode.contractValue(),
                runtimeMode = runtimeMode,
            )
            update { copy(draft = "", sending = false, commandError = null) }
            if (threadId == null) threads.selectThread(result.threadId)
        }
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
            if (repository.selectedProjectId.value != projectId) {
                threads.selectProject(projectId)
            }
            threads.selectThread(nextThreadId)
        }
    }

    private fun selectThread(threadId: String) {
        launchCommand {
            val projectId = repository.shell.value.value
                ?.threads
                ?.firstOrNull { it.id == threadId }
                ?.projectId
            if (projectId != null && projectId != repository.selectedProjectId.value) {
                threads.selectProject(projectId)
            }
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

    override fun onCleared() {
        voiceInput?.cancelRecording()
        super.onCleared()
    }

    private fun mapState(
        orchestration: OrchestrationBundle,
        environment: EnvironmentBundle,
        local: LocalState,
        favorites: List<FavoriteModelId>,
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
        val interaction = local.interactionMode.takeUnless {
            it == InteractionModeUi.DEFAULT && detail?.interactionMode == "plan"
        } ?: InteractionModeUi.PLAN
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
            timeline = detail?.let { timeline(it, local) }.orEmpty(),
            connection = environment.connection.toUi(),
            composer = ComposerUiState(
                draft = local.draft,
                selectedInteractionMode = interaction,
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
                        supportingText = it.updatedAt,
                        archived = it.archivedAt != null,
                        active = it.session?.status in ActiveSessionStatuses,
                    )
                },
                settledThreads = pickerThreads.filter { it.isSettled() }.map {
                    ThreadPickerItemUi(
                        id = it.id,
                        title = it.title,
                        supportingText = it.updatedAt,
                        archived = it.archivedAt != null,
                        active = it.session?.status in ActiveSessionStatuses,
                    )
                },
                selectedThreadId = orchestration.threadId,
                showArchived = local.showArchived,
                showSettled = local.showSettled,
                loading = orchestration.threadId != null && detail?.id != orchestration.threadId,
            ),
            activePicker = local.activePicker,
            isTurnRunning = detail?.session?.status in ActiveSessionStatuses,
            canRenameThread = detail != null,
            canArchiveThread = detail != null && detail.archivedAt == null,
            isThreadArchived = detail?.archivedAt != null,
            renameDialog = if (local.renameVisible) {
                RenameThreadUi(local.renameDraft, local.renameSaving)
            } else {
                null
            },
        )
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
        val activities = detail.activities.filter {
            if (
                it.kind == "tool.started" ||
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
            TimedItem(
                at = it.createdAt,
                item = ChatTimelineItemUi.Activity(it.toUi(local)),
                sequence = it.sequence ?: Long.MAX_VALUE,
                lifecycleRank = activityLifecycleRank(it.kind),
                turnId = it.turnId,
                turnPhase = TurnPhaseActivity,
            )
        }
        val items = messages + activities
        val turnStartedAt = items
            .filter { it.turnId != null }
            .groupBy { requireNotNull(it.turnId) }
            .mapValues { (_, turnItems) -> turnItems.minOf { it.at } }
        val sorted = items
            .sortedWith(
                compareBy<TimedItem> { it.turnId?.let(turnStartedAt::get) ?: it.at }
                    .thenBy { it.turnId.orEmpty() }
                    .thenBy { it.turnPhase }
                    .thenBy { it.at }
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

    private fun JsonObject.toToolUi(activity: OrchestrationActivity): ChatActivityUi.Tool {
        val command = toolCommand()
        val output = string("detail")?.stripTrailingExitCode()
        val changedFiles = changedFiles()
        val title = (string("title") ?: activity.summary)
            .replace(Regex("\\s+(complete|completed)$", RegexOption.IGNORE_CASE), "")
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
            lifecycleStatus == "inProgress" || activity.kind.endsWith(".updated") -> ActivityStatusUi.RUNNING
            lifecycleStatus == "stopped" -> ActivityStatusUi.FAILED
            else -> ActivityStatusUi.PENDING
        }
        val item = (this["data"] as? JsonObject)?.get("item") as? JsonObject
        val lifecycleId = item?.string("id")
            ?: item?.string("callId")
            ?: string("callId")
            ?: string("toolCallId")
        val lifecycleKey = lifecycleId ?: listOf(
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
            lifecycleKey = lifecycleKey,
        )
    }

    private data class MappedInput(
        val orchestration: OrchestrationBundle,
        val environment: EnvironmentBundle,
        val local: LocalState,
        val favorites: List<FavoriteModelId>,
    )

    private data class LocalState(
        val draft: String = "",
        val activePicker: ChatPickerUi? = null,
        val showArchived: Boolean = false,
        val showSettled: Boolean = false,
        val providerModelId: String? = null,
        val providerOptionValues: Map<String, JsonPrimitive> = emptyMap(),
        val runtimeModeId: String? = null,
        val interactionMode: InteractionModeUi = InteractionModeUi.DEFAULT,
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
    ) {
        fun structural(): LocalState = copy(
            draft = "",
            sending = false,
            commandError = null,
        )
    }
}

private data class OrchestrationBundle(
    val config: ProjectionState<EnvironmentClientConfig>,
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
            it.lifecycleKey == tool.lifecycleKey &&
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
        .filter { it.archivedAt == null && !it.isSettled() }
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
    config: EnvironmentClientConfig?,
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
    config: EnvironmentClientConfig?,
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
    config: EnvironmentClientConfig?,
    selectedModelId: String?,
): List<SlashCommandUi> {
    val instanceId = selectedModelId?.substringBefore('/')
    return config?.providers
        ?.firstOrNull { it.instanceId == instanceId }
        ?.slashCommands
        .orEmpty()
        .map { SlashCommandUi(it.name, it.description) }
}

private val PrettyPortableJson = Json(PortableJson) {
    prettyPrint = true
}

private fun JsonObject.pretty(): String = PrettyPortableJson.encodeToString(this)

private fun ModelSelection.optionId(): String = optionId(instanceId, model)

private fun optionId(
    instanceId: String,
    model: String,
): String = "$instanceId/$model"

private fun InteractionModeUi.contractValue(): String = name.lowercase()

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
private const val DefaultRuntimeMode = "full-access"
private const val TurnPhaseUser = 0
private const val TurnPhaseActivity = 1
private const val TurnPhaseAssistant = 2
