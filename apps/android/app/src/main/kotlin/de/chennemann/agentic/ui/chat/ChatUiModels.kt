package de.chennemann.agentic.ui.chat
import de.chennemann.agentic.domain.preferences.ThemePreference

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
    val canDeleteThread: Boolean = false,
    val renameDialog: RenameThreadUi? = null,
    val environmentRemoval: EnvironmentRemovalUi? = null,
    val projectCreation: ProjectCreationUi? = null,
    val projectRename: ProjectRenameUi? = null,
    val projectRemoval: ProjectRemovalUi? = null,
    val sessionTermination: SessionTerminationUi = SessionTerminationUi(),
    val threadSnooze: ThreadSnoozeUi = ThreadSnoozeUi(),
    val threadDeletion: ThreadDeletionUi? = null,
    val sharedTextImport: SharedTextImportUi? = null,
    val sharedTextImportError: String? = null,
    val shortcutError: String? = null,
    val interfaceSettings: InterfaceSettingsUi = InterfaceSettingsUi(),
    val cacheClearance: CacheClearanceUi? = null,
    val durableWork: List<DurableWorkUi> = emptyList(),
    val groqSettings: GroqSettingsUiState = GroqSettingsUiState(),
    val latestTurnChanges: LatestTurnChangesUiState = LatestTurnChangesUiState(),
)

data class ThreadSnoozeUi(
    val supported: Boolean = false,
    val isSnoozed: Boolean = false,
    val snoozedAt: String? = null,
    val snoozedUntil: String? = null,
    val wakeReason: String? = null,
    val canSnooze: Boolean = false,
    val canWake: Boolean = false,
    val actionInProgress: Boolean = false,
    val errorMessage: String? = null,
)

data class ThreadDeletionUi(
    val threadId: String,
    val title: String,
    val blockers: List<String> = emptyList(),
    val checking: Boolean = false,
    val deleting: Boolean = false,
    val errorMessage: String? = null,
) {
    val canConfirm: Boolean get() = blockers.isEmpty() && !checking && !deleting
}

data class SharedTextImportUi(
    val fingerprint: String,
    val text: String,
    val environments: List<ProjectPickerItemUi>,
    val projects: List<ProjectPickerItemUi>,
    val environmentId: String? = null,
    val projectId: String? = null,
    val importing: Boolean = false,
    val errorMessage: String? = null,
) {
    val canImport: Boolean get() = environmentId != null && projectId != null && !importing
}
data class InterfaceSettingsUi(val theme: ThemePreference = ThemePreference.SYSTEM, val interfaceScale: Float = 1f, val codeScale: Float = 1f)
data class CacheClearanceUi(val environmentId: String, val categories: List<String> = emptyList(), val clearableBytes: Long = 0, val clearing: Boolean = false, val success: Boolean = false, val errorMessage: String? = null)
data class DurableWorkUi(val commandId: String, val label: String, val threadId: String?, val failed: Boolean, val attemptCount: Long, val errorMessage: String?, val awaitsReplay: Boolean)

data class RenameThreadUi(
    val draft: String,
    val saving: Boolean = false,
)

data class EnvironmentRemovalUi(
    val environmentId: String,
    val label: String,
    val removing: Boolean = false,
    val errorMessage: String? = null,
)

data class ProjectCreationUi(
    val source: String = "",
    val creating: Boolean = false,
    val errorMessage: String? = null,
    val browsePath: String? = null,
    val parentPath: String? = null,
    val destinations: List<ProjectDestinationUi> = emptyList(),
    val browsing: Boolean = false,
)

data class ProjectDestinationUi(
    val name: String,
    val fullPath: String,
)

data class ProjectRenameUi(
    val projectId: String,
    val previousTitle: String,
    val title: String,
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

data class ProjectRemovalUi(
    val projectId: String,
    val title: String,
    val removing: Boolean = false,
    val errorMessage: String? = null,
)

data class SessionTerminationUi(
    val canTerminate: Boolean = false,
    val terminating: Boolean = false,
    val terminal: Boolean = false,
    val errorMessage: String? = null,
)

data class LatestTurnChangesUiState(
    val turnId: String? = null,
    val files: List<ChangedFileUi> = emptyList(),
)

data class ChangedFileUi(
    val path: String,
    val kind: String,
    val additions: Int,
    val deletions: Int,
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

    data class ToolGroup(
        override val id: String,
        val activities: List<ChatActivityUi.Tool>,
    ) : ChatTimelineItemUi
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
        val lifecycleKey: String? = null,
        val lifecycleFallbackKey: String? = null,
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

    data class ProposedPlan(
        override val id: String,
        override val summary: String,
        val planMarkdown: String,
        val canContinue: Boolean,
        val continuing: Boolean = false,
        val errorMessage: String? = null,
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
    val snoozedUntil: String? = null,
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
    val selectedProviderModelId: String? = null,
    val providerModels: List<ProviderModelOptionUi> = emptyList(),
    val providerOptions: List<ProviderOptionUi> = emptyList(),
    val selectedRuntimeModeId: String? = null,
    val runtimeModes: List<RuntimeModeOptionUi> = emptyList(),
    val slashCommands: List<SlashCommandUi> = emptyList(),
    val quickSwitchProjects: List<ProjectQuickSwitchUi> = emptyList(),
    val sending: Boolean = false,
    val errorMessage: String? = null,
    val enabled: Boolean = true,
    val voiceInputAvailable: Boolean = false,
    val voiceInputStatus: VoiceInputStatusUi = VoiceInputStatusUi.IDLE,
)

enum class VoiceInputStatusUi {
    IDLE,
    RECORDING,
    TRANSCRIBING,
}

data class GroqSettingsUiState(
    val dialogVisible: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

data class ProjectQuickSwitchUi(
    val id: String,
    val label: String,
    val title: String,
    val active: Boolean = false,
    val processing: Boolean = false,
    val unreadCount: Int = 0,
    val attentionCount: Int = 0,
)

data class ProviderModelOptionUi(
    val id: String,
    val providerInstanceId: String,
    val providerLabel: String,
    val modelLabel: String,
    val supportingText: String? = null,
    val favorite: Boolean = false,
    val favoriteOrder: Int? = null,
)

sealed interface ProviderOptionUi {
    val id: String
    val label: String
    val description: String?

    data class Select(
        override val id: String,
        override val label: String,
        override val description: String? = null,
        val values: List<ProviderOptionValueUi>,
        val selectedValueId: String,
    ) : ProviderOptionUi

    data class Toggle(
        override val id: String,
        override val label: String,
        override val description: String? = null,
        val selected: Boolean,
    ) : ProviderOptionUi
}

data class ProviderOptionValueUi(
    val id: String,
    val label: String,
    val description: String? = null,
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

val ChatConnectionUi.canRetryConnection: Boolean
    get() = when (this) {
        is ChatConnectionUi.Cached,
        is ChatConnectionUi.Reconnecting,
        is ChatConnectionUi.Blocked,
        -> true

        is ChatConnectionUi.Failed -> canRetry
        else -> false
    }

enum class ChatPickerUi {
    NAVIGATION,
    ENVIRONMENT,
    PROJECT_THREAD,
    PROVIDER_MODEL,
    RUNTIME_MODE,
    LATEST_TURN_CHANGES,
}

sealed interface ChatUiEvent {
    data class DraftChanged(
        val value: String,
    ) : ChatUiEvent

    data object DraftDiscardRequested : ChatUiEvent

    data class ProposedPlanContinueRequested(val planId: String) : ChatUiEvent

    data object MessageSubmitted : ChatUiEvent

    data object VoiceInputPressed : ChatUiEvent

    data object VoiceInputCancelled : ChatUiEvent

    data object MicrophonePermissionDenied : ChatUiEvent

    data object GroqSettingsRequested : ChatUiEvent

    data object GroqSettingsDismissed : ChatUiEvent

    data class GroqApiKeySaved(
        val apiKey: String,
    ) : ChatUiEvent

    data object GroqApiKeyRemoved : ChatUiEvent

    data object TurnInterruptRequested : ChatUiEvent

    data object SessionTerminationRequested : ChatUiEvent

    data class ProviderModelSelected(
        val id: String,
    ) : ChatUiEvent

    data class ProviderModelFavoriteChanged(
        val id: String,
        val favorite: Boolean,
    ) : ChatUiEvent

    data class ProviderSelectOptionSelected(
        val optionId: String,
        val valueId: String,
    ) : ChatUiEvent

    data class ProviderBooleanOptionChanged(
        val optionId: String,
        val selected: Boolean,
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

    data class EnvironmentRemovalRequested(val environmentId: String) : ChatUiEvent

    data object EnvironmentRemovalDismissed : ChatUiEvent

    data object EnvironmentRemovalConfirmed : ChatUiEvent

    data class ProjectSelected(
        val projectId: String,
    ) : ChatUiEvent

    data object ProjectCreationRequested : ChatUiEvent

    data class ProjectCreationSourceChanged(val value: String) : ChatUiEvent

    data object ProjectCreationBrowseRequested : ChatUiEvent

    data class ProjectCreationDestinationOpened(val path: String) : ChatUiEvent

    data object ProjectCreationParentOpened : ChatUiEvent

    data object ProjectCreationConfirmed : ChatUiEvent

    data object ProjectCreationDismissed : ChatUiEvent

    data class ProjectRenameRequested(val projectId: String) : ChatUiEvent

    data class ProjectRenameTitleChanged(val value: String) : ChatUiEvent

    data object ProjectRenameConfirmed : ChatUiEvent

    data object ProjectRenameDismissed : ChatUiEvent

    data class ProjectRemovalRequested(val projectId: String) : ChatUiEvent

    data object ProjectRemovalConfirmed : ChatUiEvent

    data object ProjectRemovalDismissed : ChatUiEvent

    data class ProjectQuickSwitchRequested(
        val projectId: String,
    ) : ChatUiEvent

    data class ProjectThreadsRequested(
        val projectId: String,
    ) : ChatUiEvent

    data class ThreadSelected(
        val threadId: String,
    ) : ChatUiEvent

    data class ThreadSettleRequested(
        val threadId: String,
    ) : ChatUiEvent

    data class ThreadUnsettleRequested(
        val threadId: String,
    ) : ChatUiEvent

    data class PickerThreadUnarchiveRequested(
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

    data class ThreadSnoozeRequested(val snoozedUntil: String) : ChatUiEvent

    data object ThreadWakeRequested : ChatUiEvent

    data object DeleteThreadRequested : ChatUiEvent

    data object DeleteThreadConfirmed : ChatUiEvent

    data object DeleteThreadDismissed : ChatUiEvent

    data class SharedImportEnvironmentSelected(val environmentId: String) : ChatUiEvent

    data class SharedImportProjectSelected(val projectId: String) : ChatUiEvent

    data object SharedImportConfirmed : ChatUiEvent

    data object SharedImportDiscarded : ChatUiEvent

    data object SharedImportErrorDismissed : ChatUiEvent

    data object ShortcutErrorDismissed : ChatUiEvent
    data class ThemeSelected(val value: ThemePreference) : ChatUiEvent
    data class InterfaceScaleSelected(val value: Float) : ChatUiEvent
    data class CodeScaleSelected(val value: Float) : ChatUiEvent
    data class CacheInspectionRequested(val environmentId: String) : ChatUiEvent
    data object CacheClearConfirmed : ChatUiEvent
    data object CacheClearDismissed : ChatUiEvent
    data object DurableWorkRetryRequested : ChatUiEvent

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
