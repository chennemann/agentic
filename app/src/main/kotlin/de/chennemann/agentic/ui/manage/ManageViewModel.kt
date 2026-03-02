package de.chennemann.agentic.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.session.ProjectState
import de.chennemann.agentic.domain.session.ServerState
import de.chennemann.agentic.domain.session.SessionServiceApi
import de.chennemann.agentic.domain.v2.projects.LocalProjectInfo
import de.chennemann.agentic.domain.v2.servers.ServerService
import de.chennemann.agentic.domain.v2.projects.ProjectService
import de.chennemann.agentic.domain.v2.servers.LocalServerInfo
import de.chennemann.agentic.navigation.LogsRoute
import de.chennemann.agentic.navigation.NavEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ManageViewModel(
    private val service: SessionServiceApi,
    private val serverService: ServerService,
    private val projectService: ProjectService,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val lane = dispatchers.default.limitedParallelism(1)
    private val navFlow = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    private val connectedServers: Flow<List<LocalServerInfo>> = serverService.connectedServers()
    val projects = projectService.observeProjects()

    val nav = navFlow.asSharedFlow()

    val state: StateFlow<ManageUiState> = combine(service.state, projects) { global, projects ->
            val displayed = projects.map(::displayProject)
            ManageUiState(
                url = global.url,
                discovered = global.discovered,
                status = global.status,
                loadingProjects = global.loadingProjects,
                projects = displayed,
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
                viewModelScope.launch(lane) {
                    projectService.togglePinnedById(event.projectId)
                }
            }

            is ManageEvent.ProjectRemoved -> {
                service.removeProject(event.worktree)
            }

            ManageEvent.ProjectsRefreshRequested -> {
                viewModelScope.launch {
                    connectedServers.lastOrNull()?.forEach {
                        projectService.syncServerProjects(it.id, it.url)
                    }
                }
            }

            ManageEvent.LogsRequested -> {
                navFlow.tryEmit(NavEvent.NavigateTo(LogsRoute))
            }

            ManageEvent.BackRequested -> {
                navFlow.tryEmit(NavEvent.NavigateBack)
            }
        }
    }

    private fun displayProject(project: LocalProjectInfo): ProjectState {
        return ProjectState(
            id = project.id,
            worktree = project.path,
            name = folderName(project.path),
            sandboxes = emptyList(),
            favorite = project.pinned,
        )
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
