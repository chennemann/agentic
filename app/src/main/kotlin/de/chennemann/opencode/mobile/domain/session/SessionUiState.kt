package de.chennemann.opencode.mobile.domain.session

data class ProjectState(
    val id: String,
    val worktree: String,
    val name: String,
    val sandboxes: List<String> = emptyList(),
    val favorite: Boolean = false,
)

data class CommandState(
    val name: String,
    val description: String? = null,
    val source: String? = null,
)

data class SessionState(
    val id: String,
    val title: String,
    val version: String,
    val directory: String,
    val parentId: String? = null,
    val updatedAt: Long? = null,
    val archivedAt: Long? = null,
)

data class MessageState(
    val id: String,
    val role: String,
    val text: String,
    val sort: String,
    val createdAt: Long? = null,
    val completedAt: Long? = null,
    val toolCalls: List<ToolCallState> = emptyList(),
)

data class ToolCallState(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val status: String? = null,
    val sessionId: String? = null,
    val details: List<String>,
)

data class SessionUiState(
    val url: String,
    val discovered: String?,
    val status: ServerState,
    val projects: List<ProjectState>,
    val selectedProject: String?,
    val commands: List<CommandState>,
    val sessions: List<SessionState>,
    val activeSessions: List<SessionState>,
    val focusedSession: SessionState?,
    val focusedMessages: List<MessageState>,
    val canLoadMoreMessages: Boolean,
    val loadingMoreMessages: Boolean,
    val loadingProjects: Boolean,
    val loadingSessions: Boolean,
    val sessionRecentOnly: Boolean,
    val quickPinInclude: Set<String> = emptySet(),
    val quickPinExclude: Set<String> = emptySet(),
    val quickProcessing: Set<String> = emptySet(),
    val quickUnread: Set<String> = emptySet(),
    val message: String?,
)
