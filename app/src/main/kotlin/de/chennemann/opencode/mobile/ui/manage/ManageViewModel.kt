package de.chennemann.opencode.mobile.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.domain.session.ServerState
import de.chennemann.opencode.mobile.domain.session.SessionServiceApi
import de.chennemann.opencode.mobile.navigation.LogsRoute
import de.chennemann.opencode.mobile.navigation.NavEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class ManageViewModel(
    private val service: SessionServiceApi,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val lane = dispatchers.default.limitedParallelism(1)

    private data class LocalState(
        val projectPath: String,
        val projectQuery: String,
    )

    private val local = MutableStateFlow(
        LocalState(
            projectPath = service.state.value.selectedProject.orEmpty(),
            projectQuery = "",
        )
    )
    private val navFlow = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)

    val nav = navFlow.asSharedFlow()

    val state: StateFlow<ManageUiState> = combine(service.state, local) { global, local ->
        val listed = global.projects.map(::displayProject)
        val projects = filterProjects(listed, local.projectQuery)
        ManageUiState(
            url = global.url,
            discovered = global.discovered,
            status = global.status,
            projectPath = local.projectPath,
            projectQuery = local.projectQuery,
            loadingProjects = global.loadingProjects,
            favoriteProjects = projects.filter { it.favorite },
            otherProjects = projects.filterNot { it.favorite },
            selectedProject = global.selectedProject,
            message = global.message,
        )
    }
        .flowOn(lane)
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ManageUiState(
            url = service.state.value.url,
            discovered = service.state.value.discovered,
            status = ServerState.Idle,
            projectPath = service.state.value.selectedProject.orEmpty(),
            projectQuery = "",
            loadingProjects = false,
            favoriteProjects = emptyList(),
            otherProjects = emptyList(),
            selectedProject = null,
            message = null,
        ),
    )

    init {
        service.start(viewModelScope)
    }

    fun onEvent(event: ManageEvent) {
        when (event) {
            is ManageEvent.Connect -> {
                service.updateUrl(event.url)
                service.refresh()
            }
            is ManageEvent.ProjectPathChanged -> {
                local.value = local.value.copy(projectPath = event.value)
            }

            is ManageEvent.ProjectQueryChanged -> {
                local.value = local.value.copy(projectQuery = event.value)
            }

            ManageEvent.LoadProjectRequested -> {
                val worktree = local.value.projectPath.trim()
                if (worktree.isBlank()) return
                service.selectProject(worktree)
                local.value = local.value.copy(
                    projectPath = worktree,
                )
            }

            is ManageEvent.ProjectSelected -> {
                local.value = local.value.copy(
                    projectPath = event.worktree,
                )
                service.selectProject(event.worktree)
            }

            is ManageEvent.ProjectFavoriteToggled -> {
                service.toggleProjectFavorite(event.worktree)
            }

            is ManageEvent.ProjectRemoved -> {
                service.removeProject(event.worktree)
            }

            ManageEvent.LogsRequested -> {
                navFlow.tryEmit(NavEvent.NavigateTo(LogsRoute))
            }

            ManageEvent.BackRequested -> {
                navFlow.tryEmit(NavEvent.NavigateBack)
            }
        }
    }

    private fun filterProjects(projects: List<ProjectState>, query: String): List<ProjectState> {
        val value = query.trim().lowercase()
        if (value.isBlank()) return projects
        return projects.filter {
            it.name.lowercase().contains(value) || it.worktree.lowercase().contains(value)
        }
    }

    private fun displayProject(project: ProjectState): ProjectState {
        return project.copy(name = folderName(project.worktree))
    }

    private fun folderName(path: String): String {
        val value = path.trimEnd('/', '\\')
        if (value.isBlank()) return path
        val slash = value.lastIndexOf('/')
        val backslash = value.lastIndexOf('\\')
        val index = maxOf(slash, backslash)
        if (index < 0) return value
        val name = value.substring(index + 1)
        if (name.isBlank()) return value
        return name
    }
}
