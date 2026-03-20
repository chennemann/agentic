package de.chennemann.agentic.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.session.ProjectState
import de.chennemann.agentic.domain.session.SessionServiceApi
import de.chennemann.agentic.domain.session.SessionState
import de.chennemann.agentic.domain.v2.session.LocalSessionInfo
import de.chennemann.agentic.domain.v2.session.SessionService
import de.chennemann.agentic.navigation.NavEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionSelectionViewModel(
    private val projectKey: String,
    private val service: SessionServiceApi,
    private val sessionService: SessionService,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val lane = dispatchers.default.limitedParallelism(1)

    private val sessions: Flow<List<SessionState>> = sessionService.sessionsOfProject(projectKey)
        .map { list -> list.map(::localSessionState) }
        .flowOn(lane)

    private data class GlobalState(
        val projects: List<ProjectState>,
        val include: Set<String>,
        val exclude: Set<String>,
    )

    private val menuLocal = MutableStateFlow<SessionSelectionUiState?>(null)
    private val navFlow = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)

    val nav = navFlow.asSharedFlow()

    private val global = service.state
        .map {
            GlobalState(
                projects = it.projects,
                include = it.quickPinInclude,
                exclude = it.quickPinExclude,
            )
        }
        .flowOn(lane)

    val state: StateFlow<SessionSelectionUiState?> = combine(global, menuLocal, sessions) { global, menu, sessions ->
        quickSwitchMenu(
            menu = mergeSessions(menu, sessions),
            projects = global.projects,
            include = global.include,
            exclude = global.exclude,
        )
    }
        .flowOn(lane)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    init {
        service.start(viewModelScope)
    }

    fun refresh() {
        val limit = menuLocal.value?.limit ?: QuickSwitchMenuPageSize
        viewModelScope.launch(lane) {
            requestSessions(limit)
        }
    }

    fun onEvent(event: SessionSelectionEvent) {
        when (event) {
            is SessionSelectionEvent.SessionSelected -> {
                onSessionSelected(event.session)
                navFlow.tryEmit(NavEvent.NavigateBack)
            }

            is SessionSelectionEvent.SessionPinToggled -> {
                onSessionPinToggle(event.session, event.systemPinned)
            }

            is SessionSelectionEvent.SessionArchiveRequested -> {
                onSessionArchiveRequested(event.session)
            }

            is SessionSelectionEvent.RenameSessionSubmitted -> {
                onRenameSessionSubmitted(event.session, event.title)
            }

            SessionSelectionEvent.MoreSessionsRequested -> {
                onMoreSessionsRequested()
            }

            SessionSelectionEvent.NewSessionRequested -> {
                onNewSessionRequested()
                navFlow.tryEmit(NavEvent.NavigateBack)
            }
        }
    }

    private fun onSessionSelected(session: SessionState) {
        service.openSession(session)
    }

    private fun onSessionPinToggle(session: SessionState, systemPinned: Boolean) {
        service.toggleSessionQuickPin(session, systemPinned)
    }

    private fun onSessionArchiveRequested(session: SessionState) {
        val menu = menuLocal.value ?: return
        val sessions = menu.sessions.filterNot { it.id == session.id }
        menuLocal.update { current ->
            if (current == null || current.key != menu.key) return@update current
            current.copy(sessions = sessions)
        }
        service.archiveSession(session)
    }

    private fun onRenameSessionSubmitted(session: SessionState, title: String) {
        val next = title.trim()
        if (next.isBlank()) return
        val menu = menuLocal.value ?: return
        menuLocal.update { current ->
            if (current == null || current.key != menu.key) return@update current
            current.copy(
                sessions = current.sessions.map {
                    if (it.id == session.id) {
                        it.copy(title = next)
                    } else {
                        it
                    }
                },
            )
        }
        service.renameSession(session, next)
    }

    private fun onMoreSessionsRequested() {
        val menu = menuLocal.value ?: return
        if (menu.loading || !menu.canLoadMore) return
        val limit = menu.limit + QuickSwitchMenuPageSize
        menuLocal.update { current ->
            if (current == null || current.key != menu.key) return@update current
            current.copy(
                loading = true,
                limit = limit,
            )
        }
        fetchQuickSwitchMenu(menu.key, menu.worktree, limit)
    }

    private fun onNewSessionRequested() {
        val worktree = menuLocal.value?.worktree
            ?: projectByKey(projectKey, service.state.value.projects)?.worktree
            ?: projectKey
        viewModelScope.launch(lane) {
            service.createSessionAndFocus(worktree)
        }
    }

    private suspend fun requestSessions(limit: Int) {
        val project = projectByKey(projectKey, service.state.value.projects)
        val worktree = project?.worktree ?: projectKey
        val name = projectLabel(project, projectKey)
        val cached = runCatching {
            service.cachedSessionsForProject(worktree, limit)
        }.getOrDefault(emptyList())
        menuLocal.value = SessionSelectionUiState(
            key = projectKey,
            worktree = worktree,
            project = name,
            sessions = cached.take(limit),
            loading = true,
            limit = limit,
            canLoadMore = cached.size >= limit,
        )
        fetchQuickSwitchMenu(projectKey, worktree, limit)
    }

    private fun fetchQuickSwitchMenu(key: String, worktree: String, limit: Int) {
        viewModelScope.launch(lane) {
            val result = runCatching { service.sessionsForProject(worktree, limit) }
            result.onSuccess { list ->
                menuLocal.update { menu ->
                    if (menu == null || menu.key != key) return@update menu
                    val sessions = list.take(limit)
                    val canLoadMore = list.size >= limit
                    if (menu.sessions == sessions && menu.canLoadMore == canLoadMore && menu.limit == limit) {
                        if (!menu.loading) return@update menu
                        return@update menu.copy(loading = false)
                    }
                    menu.copy(
                        sessions = sessions,
                        loading = false,
                        limit = limit,
                        canLoadMore = canLoadMore,
                    )
                }
            }
            result.onFailure {
                menuLocal.update { menu ->
                    if (menu == null || menu.key != key) return@update menu
                    menu.copy(loading = false)
                }
            }
        }
    }

    private fun quickSwitchMenu(
        menu: SessionSelectionUiState?,
        projects: List<ProjectState>,
        include: Set<String>,
        exclude: Set<String>,
    ): SessionSelectionUiState? {
        if (menu == null) return null
        val project = projectByKey(menu.key, projects)
        val worktree = project?.worktree ?: menu.worktree
        val favorite = project?.favorite == true
        val sessions = menu.sessions
            .filter { it.archivedAt == null && it.parentId == null }
            .groupBy { it.id }
            .mapNotNull {
                it.value.maxWithOrNull(compareBy<SessionState>({ value -> value.updatedAt ?: 0L }, { value -> value.id }))
            }
            .sortedWith(
                compareByDescending<SessionState> { it.updatedAt ?: 0L }
                    .thenByDescending { it.id },
            )
        val cutoff = System.currentTimeMillis() - QuickSwitchWindowMs
        val system = systemCycle(sessions, favorite, cutoff)
        val pinned = effectiveCycle(sessions, system, include, exclude)
            .map { it.id }
            .toSet()
        return menu.copy(
            worktree = worktree,
            project = projectLabel(project, menu.key),
            sessions = sessions,
            pinned = pinned,
            systemPinned = system.map { it.id }.toSet(),
        )
    }

    private fun mergeSessions(menu: SessionSelectionUiState?, sessions: List<SessionState>): SessionSelectionUiState? {
        if (menu == null) return null
        if (sessions.isEmpty()) return menu
        return menu.copy(
            sessions = sessions.take(menu.limit),
            canLoadMore = menu.canLoadMore || sessions.size > menu.limit,
        )
    }

    private fun systemCycle(sessions: List<SessionState>, favorite: Boolean, cutoff: Long): List<SessionState> {
        val recent = sessions.filter { (it.updatedAt ?: 0L) >= cutoff }
        if (recent.isNotEmpty()) return recent
        if (!favorite) return emptyList()
        return favoriteFallback(sessions)
    }

    private fun effectiveCycle(
        sessions: List<SessionState>,
        system: List<SessionState>,
        include: Set<String>,
        exclude: Set<String>,
    ): List<SessionState> {
        val forced = sessions.filter { include.contains(it.id) }
        val base = system.filterNot { exclude.contains(it.id) }
        return (base + forced).distinctBy { it.id }
    }

    private fun favoriteFallback(sessions: List<SessionState>): List<SessionState> {
        val latest = sessions
            .maxWithOrNull(compareBy<SessionState>({ it.updatedAt ?: 0L }, { it.id }))
            ?: return emptyList()
        val updatedAt = latest.updatedAt ?: return listOf(latest)
        val cutoff = updatedAt - QuickSwitchFavoriteWindowMs
        val list = sessions.filter { (it.updatedAt ?: Long.MIN_VALUE) >= cutoff }
        if (list.isEmpty()) return listOf(latest)
        return list
    }

    private fun projectByKey(key: String, projects: List<ProjectState>): ProjectState? {
        val workspace = workspaceId(key)
        return projects.firstOrNull { workspaceId(it.worktree) == workspace }
    }

    private fun projectLabel(project: ProjectState?, fallback: String): String {
        if (project == null) return folderName(fallback)
        val name = project.name.trim()
        if (name.isNotBlank()) return name
        return folderName(project.worktree)
    }

    private fun folderName(path: String): String {
        val value = path.trim().trimEnd('/', '\\')
        if (value.isBlank()) return path
        val index = maxOf(value.lastIndexOf('/'), value.lastIndexOf('\\'))
        if (index < 0) return value
        val name = value.substring(index + 1)
        if (name.isBlank()) return value
        return name
    }

    private fun workspaceId(path: String): String {
        return path.trimEnd('/', '\\')
    }
}

private const val QuickSwitchMenuPageSize = 11
private const val QuickSwitchWindowMs = 2 * 60 * 60 * 1000L
private const val QuickSwitchFavoriteWindowMs = 30 * 60 * 1000L
private const val LocalSessionVersionPlaceholder = "local"

private fun localSessionState(value: LocalSessionInfo): SessionState {
    return SessionState(
        id = value.id,
        title = value.title,
        version = LocalSessionVersionPlaceholder,
        directory = value.workspace,
        parentId = value.parentId,
        updatedAt = value.updatedAt,
        archivedAt = value.archivedAt,
    )
}
