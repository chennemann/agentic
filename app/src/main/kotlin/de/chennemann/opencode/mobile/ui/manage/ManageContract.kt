package de.chennemann.opencode.mobile.ui.manage

import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.domain.session.ServerState

data class ManageUiState(
    val url: String,
    val discovered: String?,
    val status: ServerState,
    val loadingProjects: Boolean,
    val projects: List<ProjectState>,
    val selectedProject: String?,
    val message: String?,
)

sealed interface ManageEvent {

    data class Connect(val url: String) : ManageEvent

    data class LoadProjectRequested(val worktree: String) : ManageEvent

    data class ProjectSelected(val worktree: String) : ManageEvent

    data class ProjectFavoriteToggled(val projectId: String) : ManageEvent

    data class ProjectRemoved(val worktree: String) : ManageEvent

    data object LogsRequested : ManageEvent

    data object BackRequested : ManageEvent
}
