package de.chennemann.agentic.ui.chat

data class ChatUiState(
    val title: String,
    val environmentLabel: String,
    val projectLabel: String?,
    val threadId: String?,
    val timeline: List<ChatTimelineItemUi>,
    val connection: ChatConnectionUi,
    val composer: ComposerUiState,
    val environmentPicker: EnvironmentPickerUiState = EnvironmentPickerUiState(),
    val threadPicker: ThreadPickerUiState = ThreadPickerUiState(),
    val activePicker: ChatPickerUi? = null,
    val isTurnRunning: Boolean = false,
    val canRenameThread: Boolean = false,
    val canArchiveThread: Boolean = false,
    val isThreadArchived: Boolean = false,
    val renameDialog: RenameThreadUi? = null,
)

data class RenameThreadUi(
    val draft: String,
    val saving: Boolean = false,
)

sealed interface ChatTimelineItemUi {
    val id: String

    data class Message(
        val value: ChatMessageUi,
    ) : ChatTimelineItemUi {
        override val id: String = value.id
    }

    data class Activity(
        val value: ChatActivityUi,
    ) : ChatTimelineItemUi {
        override val id: String = value.id
    }
}

data class ChatMessageUi(
    val id: String,
    val author: ChatMessageAuthorUi,
    val content: String,
    val isStreaming: Boolean = false,
    val supportingText: String? = null,
)

enum class ChatMessageAuthorUi {
    USER,
    ASSISTANT,
    SYSTEM,
}

sealed interface ChatActivityUi {
    val id: String
    val summary: String

    data class Information(
        override val id: String,
        override val summary: String,
        val detail: String? = null,
    ) : ChatActivityUi

    data class Tool(
        override val id: String,
        override val summary: String,
        val subtitle: String? = null,
        val status: ActivityStatusUi = ActivityStatusUi.PENDING,
        val detail: String? = null,
    ) : ChatActivityUi

    data class Approval(
        override val id: String,
        val request: PendingApprovalUi,
    ) : ChatActivityUi {
        override val summary: String = request.title
    }

    data class UserInput(
        override val id: String,
        val request: PendingUserInputUi,
    ) : ChatActivityUi {
        override val summary: String = request.title
    }

    data class Error(
        override val id: String,
        override val summary: String,
        val detail: String? = null,
        val canRetry: Boolean = false,
    ) : ChatActivityUi

    data class Unknown(
        override val id: String,
        override val summary: String,
        val formattedDetail: String,
        val typeLabel: String? = null,
    ) : ChatActivityUi
}

enum class ActivityStatusUi {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

data class PendingApprovalUi(
    val requestId: String,
    val title: String,
    val description: String?,
    val decisions: List<ApprovalDecisionUi>,
    val responseInFlight: Boolean = false,
)

enum class ApprovalDecisionUi(
    val label: String,
) {
    ACCEPT("Allow"),
    ACCEPT_FOR_SESSION("Allow for session"),
    DECLINE("Decline"),
    CANCEL("Cancel"),
}

data class PendingUserInputUi(
    val requestId: String,
    val title: String,
    val description: String?,
    val questions: List<UserInputQuestionUi>,
    val responseInFlight: Boolean = false,
    val canSubmit: Boolean = true,
)

data class UserInputQuestionUi(
    val id: String,
    val label: String,
    val description: String? = null,
    val required: Boolean = false,
    val allowsMultiple: Boolean = false,
    val options: List<UserInputOptionUi> = emptyList(),
    val textAnswer: String = "",
    val selectedOptionIds: Set<String> = emptySet(),
)

data class UserInputOptionUi(
    val id: String,
    val label: String,
    val description: String? = null,
)

data class ThreadPickerUiState(
    val projects: List<ProjectPickerItemUi> = emptyList(),
    val selectedProjectId: String? = null,
    val threads: List<ThreadPickerItemUi> = emptyList(),
    val settledThreads: List<ThreadPickerItemUi> = emptyList(),
    val selectedThreadId: String? = null,
    val showArchived: Boolean = false,
    val showSettled: Boolean = false,
    val loading: Boolean = false,
)

data class ProjectPickerItemUi(
    val id: String,
    val label: String,
    val supportingText: String? = null,
)

data class ThreadPickerItemUi(
    val id: String,
    val title: String,
    val supportingText: String? = null,
    val archived: Boolean = false,
    val active: Boolean = false,
)

data class EnvironmentPickerUiState(
    val environments: List<EnvironmentPickerItemUi> = emptyList(),
    val selectedEnvironmentId: String? = null,
)

data class EnvironmentPickerItemUi(
    val id: String,
    val label: String,
    val supportingText: String? = null,
    val connection: EnvironmentConnectionIndicatorUi = EnvironmentConnectionIndicatorUi.OFFLINE,
)

enum class EnvironmentConnectionIndicatorUi {
    LIVE,
    CONNECTING,
    CACHED,
    OFFLINE,
    BLOCKED,
}

data class ComposerUiState(
    val draft: String = "",
    val selectedInteractionMode: InteractionModeUi = InteractionModeUi.DEFAULT,
    val selectedProviderModelId: String? = null,
    val providerModels: List<ProviderModelOptionUi> = emptyList(),
    val selectedRuntimeModeId: String? = null,
    val runtimeModes: List<RuntimeModeOptionUi> = emptyList(),
    val slashCommands: List<SlashCommandUi> = emptyList(),
    val quickSwitchProjects: List<ProjectQuickSwitchUi> = emptyList(),
    val sending: Boolean = false,
    val enabled: Boolean = true,
)

data class ProjectQuickSwitchUi(
    val id: String,
    val label: String,
    val title: String,
    val active: Boolean = false,
    val processing: Boolean = false,
    val attentionCount: Int = 0,
)

data class ProviderModelOptionUi(
    val id: String,
    val providerInstanceId: String,
    val providerLabel: String,
    val modelLabel: String,
    val supportingText: String? = null,
)

data class RuntimeModeOptionUi(
    val id: String,
    val label: String,
    val description: String? = null,
)

data class SlashCommandUi(
    val name: String,
    val description: String? = null,
)

enum class InteractionModeUi(
    val label: String,
) {
    DEFAULT("Default"),
    PLAN("Plan"),
}

sealed interface ChatConnectionUi {
    data object Live : ChatConnectionUi

    data class Cached(
        val message: String = "Showing saved conversation",
    ) : ChatConnectionUi

    data class Connecting(
        val message: String = "Connecting…",
    ) : ChatConnectionUi

    data class Synchronizing(
        val message: String = "Catching up…",
    ) : ChatConnectionUi

    data class Reconnecting(
        val message: String = "Connection lost. Reconnecting…",
    ) : ChatConnectionUi

    data class Blocked(
        val message: String,
    ) : ChatConnectionUi

    data class Unsupported(
        val message: String,
    ) : ChatConnectionUi

    data class Failed(
        val message: String,
        val canRetry: Boolean = true,
    ) : ChatConnectionUi
}

enum class ChatPickerUi {
    ENVIRONMENT,
    PROJECT_THREAD,
    PROVIDER_MODEL,
    RUNTIME_MODE,
}

sealed interface ChatUiEvent {
    data class DraftChanged(
        val value: String,
    ) : ChatUiEvent

    data object MessageSubmitted : ChatUiEvent

    data object TurnInterruptRequested : ChatUiEvent

    data class InteractionModeSelected(
        val mode: InteractionModeUi,
    ) : ChatUiEvent

    data class ProviderModelSelected(
        val id: String,
    ) : ChatUiEvent

    data class RuntimeModeSelected(
        val id: String,
    ) : ChatUiEvent

    data class SlashCommandSelected(
        val name: String,
    ) : ChatUiEvent

    data class PickerRequested(
        val picker: ChatPickerUi,
    ) : ChatUiEvent

    data object PickerDismissed : ChatUiEvent

    data class EnvironmentSelected(
        val environmentId: String,
    ) : ChatUiEvent

    data object PairEnvironmentRequested : ChatUiEvent

    data class ProjectSelected(
        val projectId: String,
    ) : ChatUiEvent

    data class ProjectQuickSwitchRequested(
        val projectId: String,
    ) : ChatUiEvent

    data class ProjectThreadsRequested(
        val projectId: String,
    ) : ChatUiEvent

    data class ThreadSelected(
        val threadId: String,
    ) : ChatUiEvent

    data class ArchivedThreadsVisibilityChanged(
        val visible: Boolean,
    ) : ChatUiEvent

    data class SettledThreadsVisibilityChanged(
        val visible: Boolean,
    ) : ChatUiEvent

    data object NewThreadRequested : ChatUiEvent

    data object RenameThreadRequested : ChatUiEvent

    data class RenameThreadDraftChanged(
        val value: String,
    ) : ChatUiEvent

    data object RenameThreadConfirmed : ChatUiEvent

    data object RenameThreadDismissed : ChatUiEvent

    data object ArchiveThreadRequested : ChatUiEvent

    data object UnarchiveThreadRequested : ChatUiEvent

    data class ActivityExpansionChanged(
        val activityId: String,
        val expanded: Boolean,
    ) : ChatUiEvent

    data class ApprovalDecisionSelected(
        val requestId: String,
        val decision: ApprovalDecisionUi,
    ) : ChatUiEvent

    data class UserInputTextChanged(
        val requestId: String,
        val questionId: String,
        val value: String,
    ) : ChatUiEvent

    data class UserInputOptionToggled(
        val requestId: String,
        val questionId: String,
        val optionId: String,
    ) : ChatUiEvent

    data class UserInputSubmitted(
        val requestId: String,
    ) : ChatUiEvent

    data class ActivityRetryRequested(
        val activityId: String,
    ) : ChatUiEvent

    data object ConnectionRetryRequested : ChatUiEvent
}
