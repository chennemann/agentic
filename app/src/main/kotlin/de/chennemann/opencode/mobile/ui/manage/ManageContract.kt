package de.chennemann.opencode.mobile.ui.manage

import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.domain.session.ServerState

data class ManageUiState(
    val url: String,
    val discovered: String?,
    val status: ServerState,
    val projectPath: String,
    val projectQuery: String,
    val loadingProjects: Boolean,
    val favoriteProjects: List<ProjectState>,
    val otherProjects: List<ProjectState>,
    val selectedProject: String?,
    val message: String?,
)

sealed interface ManageEvent {

    data class Connect(val url: String) : ManageEvent

    data class ProjectPathChanged(val value: String) : ManageEvent

    data class ProjectQueryChanged(val value: String) : ManageEvent

    data object LoadProjectRequested : ManageEvent

    data class ProjectSelected(val worktree: String) : ManageEvent

    data class ProjectFavoriteToggled(val worktree: String) : ManageEvent

    data class ProjectRemoved(val worktree: String) : ManageEvent

    data object LogsRequested : ManageEvent

    data object BackRequested : ManageEvent
}
