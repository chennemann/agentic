package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.domain.session.SessionState

data class SessionSelectionUiState(
    val key: String,
    val worktree: String,
    val project: String,
    val sessions: List<SessionState>,
    val loading: Boolean,
    val limit: Int = 11,
    val canLoadMore: Boolean = false,
    val pinned: Set<String> = emptySet(),
    val systemPinned: Set<String> = emptySet(),
)

sealed interface SessionSelectionEvent {
    data class SessionSelected(val session: SessionState) : SessionSelectionEvent

    data class SessionPinToggled(val session: SessionState, val systemPinned: Boolean) : SessionSelectionEvent

    data class SessionArchiveRequested(val session: SessionState) : SessionSelectionEvent

    data class RenameSessionSubmitted(val session: SessionState, val title: String) : SessionSelectionEvent

    data object MoreSessionsRequested : SessionSelectionEvent

    data object NewSessionRequested : SessionSelectionEvent
}
