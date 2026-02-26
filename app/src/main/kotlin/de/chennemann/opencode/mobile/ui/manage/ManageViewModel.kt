package de.chennemann.opencode.mobile.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.domain.session.ServerState
import de.chennemann.opencode.mobile.domain.session.SessionServiceApi
import de.chennemann.opencode.mobile.domain.session.SessionState
import de.chennemann.opencode.mobile.navigation.AgentChatRoute
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
import kotlinx.coroutines.launch

class ManageViewModel(
    private val service: SessionServiceApi,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val lane = dispatchers.default.limitedParallelism(1)

    private data class LocalState(
        val projectPath: String,
        val projectQuery: String,
        val selectedWorkspace: String?,
        val sessionScroll: Long,
    )

    private val local = MutableStateFlow(
        LocalState(
            projectPath = service.state.value.selectedProject.orEmpty(),
            projectQuery = "",
            selectedWorkspace = service.state.value.selectedProject,
            sessionScroll = 0L,
        )
    )
    private val navFlow = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)

    val nav = navFlow.asSharedFlow()

    val state: StateFlow<ManageUiState> = combine(service.state, local) { global, local ->
        val listed = global.projects.map(::displayProject)
        val projects = filterProjects(listed, local.projectQuery)
        val workspaces = workspaceOptions(global.projects, global.selectedProject)
        val selectedWorkspace = selectedWorkspace(workspaces, local.selectedWorkspace)
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
            selectedProjectName = selectedProjectName(listed, global.selectedProject),
            sessionScroll = local.sessionScroll,
            loadingSessions = global.loadingSessions,
            workspaceOptions = workspaces,
            selectedWorkspace = selectedWorkspace?.directory,
            selectedWorkspaceName = selectedWorkspace?.title,
            sessionSections = sessionSections(global.sessions, workspaces),
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
            selectedProjectName = null,
            sessionScroll = 0L,
            loadingSessions = false,
            workspaceOptions = emptyList(),
            selectedWorkspace = null,
            selectedWorkspaceName = null,
            sessionSections = emptyList(),
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
                    selectedWorkspace = worktree,
                    sessionScroll = local.value.sessionScroll + 1,
                )
            }

            is ManageEvent.ProjectSelected -> {
                local.value = local.value.copy(
                    projectPath = event.worktree,
                    selectedWorkspace = event.worktree,
                    sessionScroll = local.value.sessionScroll + 1,
                )
                service.selectProject(event.worktree)
            }

            is ManageEvent.ProjectFavoriteToggled -> {
                service.toggleProjectFavorite(event.worktree)
            }

            is ManageEvent.ProjectRemoved -> {
                service.removeProject(event.worktree)
            }

            is ManageEvent.WorkspaceSelected -> {
                local.value = local.value.copy(selectedWorkspace = event.directory)
            }

            is ManageEvent.SessionRequested -> {
                val sessionId = event.sessionId
                if (sessionId == null) {
                    viewModelScope.launch(lane) {
                        val workspaces = workspaceOptions(service.state.value.projects, service.state.value.selectedProject)
                        val selected = selectedWorkspace(workspaces, local.value.selectedWorkspace)
                        val directory = selected?.directory ?: return@launch
                        if (service.createSessionAndFocus(directory)) {
                            navFlow.tryEmit(NavEvent.NavigateTo(AgentChatRoute))
                        }
                    }
                    return
                }

                val session = findSessionById(sessionId) ?: return
                service.openSession(session)
                navFlow.tryEmit(NavEvent.NavigateTo(AgentChatRoute))
            }

            ManageEvent.LogsRequested -> {
                navFlow.tryEmit(NavEvent.NavigateTo(LogsRoute))
            }

            ManageEvent.BackRequested -> {
                navFlow.tryEmit(NavEvent.NavigateBack)
            }
        }
    }

    private fun findSessionById(id: String): SessionState? {
        return (service.state.value.sessions + service.state.value.activeSessions)
            .firstOrNull { it.id == id }
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

    private fun selectedProjectName(projects: List<ProjectState>, selected: String?): String? {
        if (selected.isNullOrBlank()) return null
        val id = workspaceId(selected)
        return projects.firstOrNull { workspaceId(it.worktree) == id }?.name ?: folderName(selected)
    }

    private fun workspaceOptions(projects: List<ProjectState>, selected: String?): List<WorkspaceOptionState> {
        if (selected.isNullOrBlank()) return emptyList()
        val id = workspaceId(selected)
        val project = projects.firstOrNull { workspaceId(it.worktree) == id }
        val directories = if (project == null) {
            listOf(selected)
        } else {
            listOf(project.worktree) + project.sandboxes
        }
        return directories
            .map(::workspaceId)
            .distinct()
            .map {
                WorkspaceOptionState(
                    directory = it,
                    title = if (it == id) {
                        "Local: ${folderName(it)}"
                    } else {
                        "Workspace: ${folderName(it)}"
                    },
                    local = it == id,
                )
            }
    }

    private fun selectedWorkspace(options: List<WorkspaceOptionState>, selected: String?): WorkspaceOptionState? {
        if (options.isEmpty()) return null
        if (selected.isNullOrBlank()) return options.firstOrNull()
        val id = workspaceId(selected)
        return options.firstOrNull { workspaceId(it.directory) == id } ?: options.firstOrNull()
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

    private fun sessionSections(sessions: List<SessionState>, options: List<WorkspaceOptionState>): List<SessionSectionState> {
        if (sessions.isEmpty() || options.isEmpty()) return emptyList()
        return options.mapNotNull { option ->
            val list = sessions
                .filter {
                    workspaceId(it.directory) == workspaceId(option.directory) &&
                        it.parentId == null
                }
                .sortedWith(
                    compareByDescending<SessionState> { it.updatedAt ?: 0L }
                        .thenByDescending { it.id }
                )
                .take(WorkspaceSessionLimit)
            if (list.isEmpty()) return@mapNotNull null
            SessionSectionState(workspace = option, sessions = list)
        }
    }

    private fun workspaceId(path: String): String {
        val value = path.trimEnd('/', '\\')
        if (value.isBlank()) return path
        return value
    }
}

private const val WorkspaceSessionLimit = 3
