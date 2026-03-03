package de.chennemann.agentic.ui.manage

import de.chennemann.agentic.domain.session.ProjectState
import de.chennemann.agentic.domain.session.ServerState

data class ManageUiState(
    val url: String,
    val status: ServerState,
    val loadingProjects: Boolean,
    val projects: List<ProjectState>,
    val selectedProject: String?,
)

sealed interface ManageEvent {

    data class Connect(val url: String) : ManageEvent

    data class LoadProjectRequested(val worktree: String) : ManageEvent

    data class ProjectSelected(val worktree: String) : ManageEvent

    data class ProjectFavoriteToggled(val projectId: String) : ManageEvent

    data class ProjectRemoved(val worktree: String) : ManageEvent

    data object ProjectsRefreshRequested : ManageEvent

    data object LogsRequested : ManageEvent

    data object BackRequested : ManageEvent
}
