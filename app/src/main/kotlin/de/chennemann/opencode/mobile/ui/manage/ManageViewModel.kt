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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ManageViewModel(
    private val service: SessionServiceApi,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val lane = dispatchers.default.limitedParallelism(1)
    private val navFlow = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)

    val nav = navFlow.asSharedFlow()

    val state: StateFlow<ManageUiState> = service.state
        .map { global ->
            val projects = global.projects.map(::displayProject)
            ManageUiState(
                url = global.url,
                discovered = global.discovered,
                status = global.status,
                loadingProjects = global.loadingProjects,
                projects = projects,
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
            loadingProjects = false,
            projects = emptyList(),
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
            is ManageEvent.LoadProjectRequested -> {
                val worktree = event.worktree.trim()
                if (worktree.isBlank()) return
                service.selectProject(worktree)
            }

            is ManageEvent.ProjectSelected -> {
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
