package de.chennemann.agentic.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.session.ProjectState
import de.chennemann.agentic.domain.session.ServerState
import de.chennemann.agentic.domain.v2.projects.LocalProjectInfo
import de.chennemann.agentic.domain.v2.projects.ProjectService
import de.chennemann.agentic.domain.v2.servers.ServerInfo
import de.chennemann.agentic.domain.v2.servers.ServerService
import de.chennemann.agentic.navigation.LogsRoute
import de.chennemann.agentic.navigation.NavEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ManageViewModel(
    private val serverService: ServerService,
    private val projectService: ProjectService,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val lane = dispatchers.default.limitedParallelism(1)
    private val navFlow = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)

    private val connectedServer = serverService.connectedServer
    private val projects = projectService.observeProjects()
    private val selectedProject: MutableStateFlow<String?> = MutableStateFlow(null)

    val nav = navFlow.asSharedFlow()

    val state: StateFlow<ManageUiState> = combine(connectedServer, projects, selectedProject) { server, projects, selectedProject ->
        val displayed = projects.map(::displayProject)
        when (server) {
            is ServerInfo.NONE -> ManageUiState(
                url = "",
                status = ServerState.Idle,
                loadingProjects = true,
                projects = displayed,
                selectedProject = selectedProject,
            )
            is ServerInfo.ConnectedServerInfo -> ManageUiState(
                url = server.url,
                status = ServerState.Connected(server.url),
                loadingProjects = false,
                projects = displayed,
                selectedProject = selectedProject,
            )
        }
    }
        .flowOn(lane)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ManageUiState(
                url = "",
                status = ServerState.Idle,
                loadingProjects = true,
                projects = emptyList(),
                selectedProject = null,
            ),
        )

    fun onEvent(event: ManageEvent) {
        when (event) {
            is ManageEvent.Connect -> {
                viewModelScope.launch {
                    serverService.connect(event.url)
                }
            }

            is ManageEvent.LoadProjectRequested -> {
                val worktree = event.worktree.trim()
                if (worktree.isBlank()) return
            }

            is ManageEvent.ProjectSelected -> {
                // service.selectProject(event.worktree)
            }

            is ManageEvent.ProjectFavoriteToggled -> {
                viewModelScope.launch {
                    projectService.togglePinnedById(event.projectId)
                }
            }

            is ManageEvent.ProjectRemoved -> {
                // service.removeProject(event.worktree)
            }

            ManageEvent.ProjectsRefreshRequested -> {
                viewModelScope.launch {
                    when (val server = connectedServer.first()) {
                        is ServerInfo.ConnectedServerInfo -> {
                            projectService.syncServerProjects(server.id, server.url)
                        }
                        else -> {}
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
