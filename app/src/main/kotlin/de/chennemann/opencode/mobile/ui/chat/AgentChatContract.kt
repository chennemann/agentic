package de.chennemann.opencode.mobile.ui.chat

import de.chennemann.opencode.mobile.domain.session.CommandState
import de.chennemann.opencode.mobile.domain.session.SessionState
import de.chennemann.opencode.mobile.domain.session.ServerState
import de.chennemann.opencode.mobile.domain.session.ToolCallState

data class ConversationTurnUiState(
    val id: String,
    val userText: String?,
    val toolCalls: List<ToolCallState>,
    val answerWriting: Boolean = false,
    val systemTexts: List<String>,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)

data class ConversationUiState(
    val title: String,
    val status: ServerState,
    val message: String?,
    val turns: List<ConversationTurnUiState>,
    val canLoadMoreMessages: Boolean,
    val loadingMoreMessages: Boolean,
    val scroll: Long,
    val draft: String,
    val mode: ConversationMode,
    val slashSuggestions: List<CommandState>,
    val quickSwitches: List<QuickSwitchState>,
    val focusedSessionId: String?,
)

enum class ConversationMode {
    PLAN,
    BUILD,
}

data class QuickSwitchState(
    val key: String,
    val worktree: String,
    val label: String,
    val project: String,
    val primarySessionId: String?,
    val cycleSessionIds: List<String>,
    val active: Boolean,
    val processing: Boolean,
    val unread: Int,
)



sealed interface ConversationEvent {
    data object WorkspaceHubRequested : ConversationEvent

    data class SubsessionRequested(val sessionId: String) : ConversationEvent

    data class DraftChanged(val value: String) : ConversationEvent

    data class ModeChanged(val value: ConversationMode) : ConversationEvent

    data class SlashCommandSelected(val name: String) : ConversationEvent

    data class SessionsRequested(val key: String) : ConversationEvent

    data class SessionRequested(val sessionId: String?, val worktree: String? = null) : ConversationEvent

    data class SessionPinToggled(val session: SessionState, val systemPinned: Boolean) : ConversationEvent

    data class SessionArchiveRequested(val session: SessionState) : ConversationEvent

    data class RenameSessionSubmitted(val session: SessionState, val title: String) : ConversationEvent

    data object MoreSessionsRequested : ConversationEvent

    data object MessageSubmitted : ConversationEvent

    data object RefreshRequested : ConversationEvent

    data object MoreMessagesRequested : ConversationEvent
}
