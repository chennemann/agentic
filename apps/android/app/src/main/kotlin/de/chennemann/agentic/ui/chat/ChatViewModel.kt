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
import de.chennemann.agentic.t3.contract.EnvironmentClientConfig
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.OrchestrationActivity
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.PortableJson
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatViewModel(
    private val environments: EnvironmentRepository,
    private val repository: OrchestrationRepository,
    private val connection: ConnectionController,
    private val environmentService: EnvironmentSelector,
    private val threads: ThreadActions,
    private val chat: ChatActions,
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

    val state = combine(orchestration, environment, local) { orchestration, environment, local ->
        mapState(orchestration, environment, local)
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
                copy(renameDraft = state.value.title, renameVisible = true)
            }

            is ChatUiEvent.RenameThreadDraftChanged -> update { copy(renameDraft = event.value) }
            ChatUiEvent.RenameThreadDismissed -> update { copy(renameVisible = false) }
            ChatUiEvent.RenameThreadConfirmed -> renameThread()
            ChatUiEvent.ArchiveThreadRequested -> repository.focusedThreadId.value?.let { id ->
                launchCommand { threads.archive(id) }
            }

            ChatUiEvent.UnarchiveThreadRequested -> repository.focusedThreadId.value?.let { id ->
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
        return pair?.let {
            inheritedSelections.firstOrNull { selection ->
                selection.instanceId == it.first && selection.model == it.second
            } ?: ModelSelection(it.first, it.second)
        }
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

    private fun mapState(
        orchestration: OrchestrationBundle,
        environment: EnvironmentBundle,
        local: LocalState,
    ): ChatUiState {
        val shell = orchestration.shell.value
        val detail = orchestration.thread.value?.thread?.takeIf { it.id == orchestration.threadId }
        val shellThread = shell?.threads?.firstOrNull { it.id == orchestration.threadId }
        val projectId = orchestration.projectId
            ?: detail?.projectId
            ?: shellThread?.projectId
            ?: shell?.projects?.firstOrNull()?.id
        val project = shell?.projects?.firstOrNull { it.id == projectId }
        val modelOptions = modelOptions(orchestration.config.value)
        val selectedModelId = local.providerModelId
            ?: detail?.modelSelection?.optionId()
            ?: project?.defaultModelSelection?.optionId()
            ?: modelOptions.firstOrNull()?.id
        val interaction = local.interactionMode.takeUnless {
            it == InteractionModeUi.DEFAULT && detail?.interactionMode == "plan"
        } ?: InteractionModeUi.PLAN
        val runtime = local.runtimeModeId ?: detail?.runtimeMode ?: DefaultRuntimeMode
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
                selectedRuntimeModeId = runtime,
                runtimeModes = RuntimeModes,
                slashCommands = slashCommands(orchestration.config.value, selectedModelId),
                quickSwitchProjects = quickSwitchProjects(
                    shell = shell,
                    selectedProjectId = projectId,
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
                projects = shell?.projects.orEmpty().map {
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
                it.createdAt,
                ChatTimelineItemUi.Message(
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
            when (it.kind) {
                "approval.requested" -> it.id == oldestApproval
                "user-input.requested" -> it.id == oldestUserInput
                else -> true
            }
        }.map {
            TimedItem(it.createdAt, ChatTimelineItemUi.Activity(it.toUi(local)))
        }
        return (messages + activities).sortedWith(compareBy<TimedItem> { it.at }.thenBy { it.item.id }).map { it.item }
    }

    private fun OrchestrationActivity.toUi(local: LocalState): ChatActivityUi {
        val inFlight = payload["requestId"]?.jsonPrimitive?.content in local.inFlightRequests
        return when (kind) {
            "approval.requested" -> ChatActivityUi.Approval(
                id,
                PendingApprovalUi(
                    requestId = payload["requestId"]?.jsonPrimitive?.content ?: id,
                    title = summary,
                    description = payload["command"]?.jsonPrimitive?.content,
                    decisions = payload["decisions"]?.jsonArray.orEmpty().mapNotNull {
                        approvalDecisionFromContract(it.jsonPrimitive.content)
                    },
                    responseInFlight = inFlight,
                ),
            )

            "user-input.requested" -> {
                val requestId = payload["requestId"]?.jsonPrimitive?.content ?: id
                ChatActivityUi.UserInput(
                    id,
                    PendingUserInputUi(
                        requestId = requestId,
                        title = summary,
                        description = null,
                        questions = payload["questions"]?.jsonArray.orEmpty().map { element ->
                            val question = element.jsonObject
                            val questionId = question["id"]?.jsonPrimitive?.content.orEmpty()
                            val key = answerKey(requestId, questionId)
                            UserInputQuestionUi(
                                id = questionId,
                                label = question["prompt"]?.jsonPrimitive?.content ?: questionId,
                                required = true,
                                allowsMultiple = question["multiple"]?.jsonPrimitive?.content == "true",
                                options = question["options"]?.jsonArray.orEmpty().map {
                                    UserInputOptionUi(it.jsonPrimitive.content, it.jsonPrimitive.content)
                                },
                                textAnswer = local.textAnswers[key].orEmpty(),
                                selectedOptionIds = local.optionAnswers[key].orEmpty(),
                            )
                        },
                        responseInFlight = inFlight,
                    ),
                )
            }

            else -> when (tone) {
                "tool" -> ChatActivityUi.Tool(
                    id = id,
                    summary = summary,
                    status = if (kind.endsWith(".completed")) ActivityStatusUi.COMPLETED else ActivityStatusUi.RUNNING,
                    detail = payload.pretty(),
                )

                "error" -> ChatActivityUi.Error(id, summary, payload.pretty())
                "info" -> if (kind.startsWith("status.")) {
                    ChatActivityUi.Information(id, summary, payload.pretty())
                } else {
                    ChatActivityUi.Unknown(id, summary, payload.pretty(), kind)
                }

                else -> ChatActivityUi.Unknown(id, summary, payload.pretty(), kind)
            }
        }
    }

    private data class LocalState(
        val draft: String = "",
        val activePicker: ChatPickerUi? = null,
        val showArchived: Boolean = false,
        val showSettled: Boolean = false,
        val providerModelId: String? = null,
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
    )
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
)

private val activityOrder = compareBy<OrchestrationActivity>(
    { it.sequence ?: Long.MAX_VALUE },
    { it.createdAt },
    { it.id },
)

private fun quickSwitchProjects(
    shell: OrchestrationShellSnapshot?,
    selectedProjectId: String?,
): List<ProjectQuickSwitchUi> {
    val snapshot = shell ?: return emptyList()
    return snapshot.projects
        .mapNotNull { project ->
            val projectThreads = snapshot.threads.filter {
                it.projectId == project.id &&
                    it.archivedAt == null &&
                    !it.isSettled()
            }
            val lastActiveAt = projectThreads.maxOfOrNull { it.updatedAt }
                ?: return@mapNotNull null
            ActiveProject(project, projectThreads, lastActiveAt)
        }
        .sortedWith(
            compareByDescending<ActiveProject> { it.lastActiveAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.project.title }
                .thenBy { it.project.id },
        )
        .take(MaxQuickSwitchProjects)
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

private fun modelOptions(config: EnvironmentClientConfig?): List<ProviderModelOptionUi> = config
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
            )
        }
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

private fun JsonObject.pretty(): String = Json(PortableJson) {
    prettyPrint = true
}.encodeToString(this)

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
    RuntimeModeOptionUi("approval-required", "Ask before changes"),
    RuntimeModeOptionUi("auto-accept-edits", "Auto-accept edits"),
    RuntimeModeOptionUi("auto", "Automatic"),
    RuntimeModeOptionUi("full-access", "Full access"),
)
private val ActiveSessionStatuses = setOf("starting", "running")
private const val MaxQuickSwitchProjects = 5
private const val DefaultRuntimeMode = "approval-required"
