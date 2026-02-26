package de.chennemann.opencode.mobile.domain.session

import de.chennemann.opencode.mobile.domain.message.MessageDecorator
import de.chennemann.opencode.mobile.domain.message.MessagePart
import de.chennemann.opencode.mobile.domain.message.MessagePartParser
import de.chennemann.opencode.mobile.di.CoroutineRolloutFlag
import de.chennemann.opencode.mobile.di.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

class SessionService(
    private val conn: ConnectionGateway,
    private val proj: ProjectGateway,
    private val cmd: CommandGateway,
    private val msg: MessageGateway,
    private val cache: SessionCacheGateway,
    private val log: LogGateway,
    private val parser: MessagePartParser,
    private val decorator: MessageDecorator,
    private val projector: FocusedMessageProjector,
    private val planner: SessionSyncPlanner,
    private val reducer: SessionEventReducer,
    private val streamer: SessionStreamCoordinator,
    private val reconciler: ReconcileCoordinator,
    private val dispatchers: DispatcherProvider,
    private val rollout: CoroutineRolloutFlag,
) : SessionServiceApi {
    private data class LocalState(
        val projects: List<ProjectState> = emptyList(),
        val favoriteProjects: Set<String> = emptySet(),
        val hiddenProjects: Set<String> = emptySet(),
        val quickPinInclude: Set<String> = emptySet(),
        val quickPinExclude: Set<String> = emptySet(),
        val quickProcessing: Set<String> = emptySet(),
        val quickUnread: Set<String> = emptySet(),
        val selectedProject: String? = null,
        val commands: List<CommandState> = emptyList(),
        val sessions: List<SessionState> = emptyList(),
        val activeSessions: List<SessionState> = emptyList(),
        val focusedSession: SessionState? = null,
        val focusedMessages: List<MessageState> = emptyList(),
        val canLoadMoreMessages: Boolean = false,
        val loadingMoreMessages: Boolean = false,
        val loadingProjects: Boolean = false,
        val loadingSessions: Boolean = false,
        val sessionRecentOnly: Boolean = false,
        val sessionLimit: Int = InitialSessionLimit,
        val message: String? = null,
    )

    private data class SyncPolicy(
        val interval: Long = SyncBackoffBaseMs,
        val lastCheckAt: Long = 0L,
        val lastSyncAt: Long = 0L,
        val lastSseAt: Long = 0L,
        val lastUpdatedAt: Long? = null,
        val status: String = "unknown",
        val tool: String? = null,
        val toolAt: Long = 0L,
        val checks: Boolean = true,
        val activatedAt: Long = 0L,
    )

    private data class DirectoryPolicy(
        val interval: Long = SyncBackoffBaseMs,
        val lastCheckAt: Long = 0L,
        val checks: Boolean = true,
    )

    private val input = MutableStateFlow(conn.endpoint.value)
    private val local = MutableStateFlow(LocalState())
    private val output = MutableStateFlow(
        SessionUiState(
            url = conn.endpoint.value,
            discovered = null,
            status = ServerState.Idle,
            projects = emptyList(),
            selectedProject = null,
            commands = emptyList(),
            sessions = emptyList(),
            activeSessions = emptyList(),
            focusedSession = null,
            focusedMessages = emptyList(),
            canLoadMoreMessages = false,
            loadingMoreMessages = false,
            loadingProjects = false,
            loadingSessions = false,
            sessionRecentOnly = false,
            quickPinInclude = emptySet(),
            quickPinExclude = emptySet(),
            quickProcessing = emptySet(),
            quickUnread = emptySet(),
            message = null,
        )
    )

    private val active = linkedMapOf<String, SessionState>()
    private val sessionProject = linkedMapOf<String, String?>()
    private val part = linkedMapOf<String, Map<String, MessagePart>>()
    private val role = linkedMapOf<String, String>()
    private val pending = PendingBuffer()
    private val retainPass = PassCounter()
    private val stickySort = linkedMapOf<String, MutableMap<String, String>>()
    private val order = linkedMapOf<String, String>()
    private val messageLimit = linkedMapOf<String, Int>()
    private val policy = linkedMapOf<String, SyncPolicy>()
    private val directoryPolicy = linkedMapOf<String, DirectoryPolicy>()
    private val seq = AtomicLong(System.currentTimeMillis() * 1000)
    private var manual = false
    private var stream: Job? = null
    private var observe: Job? = null
    private var reconcile: Job? = null
    private var publish: Job? = null
    private val sync = SyncCoordinator()
    private val flush = linkedMapOf<String, Job>()
    private val resolver = SessionResolver(SessionResolveCooldownMs)
    private val overlay = linkedMapOf<String, MessageState>()
    private val overlayDirty = linkedMapOf<String, LinkedHashSet<String>>()
    private val mapLock = Any()
    private var focusedDb = emptyList<MessageState>()
    private var scope: CoroutineScope? = null
    private var mutation: MutationExecutor? = null
    private var started = false
    private var focusedKey: String? = null
    private var publishToken = 0L
    private val ioLane = dispatchers.io
    private val cpuLane = dispatchers.default
    private val mutationLane = dispatchers.default.limitedParallelism(1)

    override val state: StateFlow<SessionUiState> = output.asStateFlow()

    private fun mutate(key: String? = null, block: suspend () -> Unit) {
        mutation?.launch(key, block)
    }

    private suspend fun <T> mutateAwait(key: String? = null, block: suspend () -> T): T {
        val mutation = mutation ?: return block()
        return mutation.run(key, block)
    }

    override fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        this.scope = scope
        mutation = if (rollout.useMigratedExecution) {
            PipelineMutationExecutor(MutationPipeline(scope, mutationLane))
        } else {
            LegacyMutationExecutor(scope, mutationLane)
        }
        debug(
            unit = LogUnit.system,
            event = "mutation_mode",
            message = "Mutation executor mode",
            context = mapOf("mode" to if (rollout.useMigratedExecution) "migrated" else "legacy"),
        )
        conn.start(scope)
        scope.launch {
            combine(input, conn.found, conn.status, local) { url, discovered, status, local ->
                SessionUiState(
                    url = url,
                    discovered = discovered,
                    status = status.toUi(),
                    projects = local.projects,
                    selectedProject = local.selectedProject,
                    commands = local.commands,
                    sessions = local.sessions,
                    activeSessions = local.activeSessions,
                    focusedSession = local.focusedSession,
                    focusedMessages = local.focusedMessages,
                    canLoadMoreMessages = local.canLoadMoreMessages,
                    loadingMoreMessages = local.loadingMoreMessages,
                    loadingProjects = local.loadingProjects,
                    loadingSessions = local.loadingSessions,
                    sessionRecentOnly = local.sessionRecentOnly,
                    quickPinInclude = local.quickPinInclude,
                    quickPinExclude = local.quickPinExclude,
                    quickProcessing = local.quickProcessing,
                    quickUnread = local.quickUnread,
                    message = local.message,
                )
            }.collect {
                output.value = it
            }
        }
        scope.launch(mutationLane) {
            conn.endpoint.collect {
                if (manual) return@collect
                input.value = it
            }
        }
        scope.launch(mutationLane) {
            conn.status.collect {
                if (it !is ConnectionState.Connected) return@collect
                loadProjectsNow()
            }
        }
        scope.launch(mutationLane) {
            hydrateLastNow()
        }
        reconcile(scope)
        stream(scope)
    }

    private fun ConnectionState.toUi(): ServerState {
        return when (this) {
            is ConnectionState.Idle -> ServerState.Idle
            is ConnectionState.Loading -> ServerState.Loading
            is ConnectionState.Connected -> ServerState.Connected(version)
            is ConnectionState.Failed -> ServerState.Failed(reason)
        }
    }

    override fun updateUrl(value: String) {
        mutate {
            manual = true
            input.value = value
        }
    }

    override fun useDiscovered() {
        mutate {
            val value = conn.found.value ?: return@mutate
            manual = true
            input.value = value
        }
    }

    override fun refresh() {
        mutate {
            conn.setUrl(input.value)
            conn.refresh()
        }
    }

    fun loadProjects() {
        mutate {
            loadProjectsNow()
        }
    }

    private suspend fun loadProjectsNow() {
        if (local.value.loadingProjects) return
        local.value = local.value.copy(loadingProjects = true, message = null)
        val server = serverForCache()
        val favorites = withContext(ioLane) {
            runCatching { cache.projectFavorites(server) }
                .getOrDefault(emptySet())
                .map(::workspaceId)
                .toSet()
        }
        val pins = withContext(ioLane) {
            runCatching { cache.sessionQuickPins(server) }
                .getOrDefault(SessionQuickPinCache())
        }
        val hidden = withContext(ioLane) {
            runCatching { cache.hiddenProjects(server) }
                .getOrDefault(emptySet())
                .map(::workspaceId)
                .toSet()
        }
        val result = withContext(ioLane) { runCatching { proj.projects() } }
        local.value = local.value.copy(
            loadingProjects = false,
            quickPinInclude = pins.include,
            quickPinExclude = pins.exclude,
            hiddenProjects = hidden,
        )
        result.onSuccess { list ->
            val projects = mergeProjects(
                canonicalProjects(list.map {
                    ProjectState(
                        id = it.id,
                        worktree = workspaceId(it.worktree),
                        name = if (it.name.isBlank()) projectName(it.worktree) else it.name.trim(),
                        sandboxes = it.sandboxes.map(::workspaceId),
                    )
                }),
                favorites,
                hidden,
            )
            val current = local.value.selectedProject?.let(::workspaceId)
            val next = if (current != null && projects.any { workspaceId(it.worktree) == current }) {
                current
            } else {
                projects.firstOrNull()?.worktree
            }
            local.value = local.value.copy(
                projects = projects,
                favoriteProjects = favorites,
                hiddenProjects = hidden,
                selectedProject = next,
                commands = if (next == null) emptyList() else local.value.commands,
                sessions = if (next == null) emptyList() else local.value.sessions,
                sessionRecentOnly = false,
                sessionLimit = InitialSessionLimit,
            )
            preloadFavoriteSessions(projects)
            if (next == null) return@onSuccess
            markProjectActivated(next)
            loadSessions(next)
            loadCommands(next)
        }
        result.onFailure {
            local.value = local.value.copy(message = it.message ?: "Failed to load projects")
        }
    }

    override fun selectProject(worktree: String) {
        mutate {
            val selected = workspaceId(worktree)
            markProjectActivated(selected)
            local.value = local.value.copy(
                selectedProject = selected,
                commands = emptyList(),
                sessions = emptyList(),
                sessionRecentOnly = false,
                sessionLimit = InitialSessionLimit,
            )
            loadSessions(selected)
            loadCommands(selected)
        }
    }

    override fun toggleProjectFavorite(worktree: String) {
        mutate {
            val value = workspaceId(worktree)
            if (value.isBlank()) return@mutate
            val scope = scope ?: return@mutate
            val server = serverForCache()
            val projects = local.value.projects
            val current = local.value.favoriteProjects
            val hidden = local.value.hiddenProjects
            val next = if (current.contains(value)) {
                current - value
            } else {
                current + value
            }

            local.value = local.value.copy(
                projects = mergeProjects(projects, next, hidden),
                favoriteProjects = next,
            )
            if (next.contains(value)) {
                markProjectActivated(value)
                preloadFavoriteSessions(local.value.projects)
            }

            scope.launch(ioLane) {
                runCatching {
                    cache.setProjectFavorite(server, value, next.contains(value))
                }.onFailure {
                    mutate {
                        local.value = local.value.copy(
                            projects = mergeProjects(projects, current, hidden),
                            favoriteProjects = current,
                            message = it.message ?: "Failed to update project favorite",
                        )
                    }
                }
            }
        }
    }

    override fun removeProject(worktree: String) {
        mutate {
            val value = workspaceId(worktree)
            if (value.isBlank()) return@mutate
            val scope = scope ?: return@mutate
            val server = serverForCache()
            val projects = local.value.projects
            val favorites = local.value.favoriteProjects
            val current = local.value.hiddenProjects
            if (current.contains(value)) return@mutate
            val next = current + value

            local.value = local.value.copy(
                projects = mergeProjects(projects, favorites, next),
                hiddenProjects = next,
            )

            scope.launch(ioLane) {
                runCatching {
                    cache.setProjectHidden(server, value, true)
                }.onFailure {
                    mutate {
                        local.value = local.value.copy(
                            projects = mergeProjects(projects, favorites, current),
                            hiddenProjects = current,
                            message = it.message ?: "Failed to remove project",
                        )
                    }
                }
            }
        }
    }

    override fun toggleSessionQuickPin(session: SessionState, systemPinned: Boolean) {
        mutate {
            val id = session.id.trim()
            if (id.isBlank()) return@mutate
            val current = local.value
            val effective = (systemPinned && !current.quickPinExclude.contains(id)) || current.quickPinInclude.contains(id)
            val target = !effective
            val nextInclude = if (target && !systemPinned) {
                current.quickPinInclude + id
            } else {
                current.quickPinInclude - id
            }
            val nextExclude = if (!target && systemPinned) {
                current.quickPinExclude + id
            } else {
                current.quickPinExclude - id
            }
            local.value = local.value.copy(
                quickPinInclude = nextInclude,
                quickPinExclude = nextExclude,
            )
            if (target) {
                upsertActiveSession(session, projectForDirectory(session.directory), persist = false)
                local.value = local.value.copy(activeSessions = active.values.sortedByDescending { it.id })
            }
            val scope = scope ?: return@mutate
            val server = serverForCache()
            scope.launch(ioLane) {
                runCatching {
                    cache.setSessionQuickPins(server, nextInclude, nextExclude)
                }.onFailure {
                    mutate {
                        local.value = local.value.copy(
                            quickPinInclude = current.quickPinInclude,
                            quickPinExclude = current.quickPinExclude,
                            message = it.message ?: "Failed to update session pin",
                        )
                    }
                }
            }
        }
    }

    fun loadMoreSessions() {
        mutate {
            val worktree = local.value.selectedProject ?: return@mutate
            val limit = if (local.value.sessionRecentOnly) {
                InitialSessionLimit + SessionLimitStep
            } else {
                local.value.sessionLimit + SessionLimitStep
            }
            local.value = local.value.copy(
                sessionRecentOnly = false,
                sessionLimit = limit,
            )
            loadSessions(worktree)
        }
    }

    fun createSession() {
        mutate {
            val worktree = local.value.focusedSession?.directory ?: local.value.selectedProject ?: return@mutate
            createSessionAndFocusNow(worktree)
        }
    }

    suspend fun createSessionAndFocus(): Boolean {
        return mutateAwait {
            val worktree = local.value.focusedSession?.directory ?: local.value.selectedProject ?: return@mutateAwait false
            createSessionAndFocusNow(worktree)
        }
    }

    override suspend fun createSessionAndFocus(worktree: String): Boolean {
        return mutateAwait {
            createSessionAndFocusNow(worktree)
        }
    }

    private suspend fun createSessionAndFocusNow(worktree: String): Boolean {
        val selected = workspaceId(worktree)
        markProjectActivated(selected)
        val previous = focusedKey
        if (previous != null) {
            flush.remove(previous)?.cancel()
        }
        focusedKey = null
        local.value = local.value.copy(
            selectedProject = selected,
            focusedSession = null,
            focusedMessages = emptyList(),
            canLoadMoreMessages = false,
            loadingMoreMessages = false,
            message = null,
        )
        loadSessions(selected)
        loadCommands(selected)
        return true
    }

    private suspend fun createSessionForMessage(worktree: String, text: String): SessionState? {
        local.value = local.value.copy(loadingSessions = true, message = null)
        val title = generatedSessionTitle(text)
        val result = withContext(ioLane) { runCatching { proj.createSession(worktree, title) } }
        local.value = local.value.copy(loadingSessions = false)
        result.onFailure {
            local.value = local.value.copy(message = it.message ?: "Failed to create session")
        }
        val created = result.getOrNull() ?: return null
        val session = SessionState(
            id = created.id,
            title = created.title,
            version = created.version,
            directory = created.directory,
            updatedAt = created.updatedAt,
            archivedAt = created.archivedAt,
        )
        focusSession(session, session.directory)
        loadSessions(local.value.selectedProject ?: worktree)
        return session
    }

    override fun openSession(session: SessionState) {
        mutate {
            focusSession(session, session.directory)
        }
    }

    override fun send(text: String, agent: String) {
        mutate {
            val value = text.trim()
            if (value.isBlank()) return@mutate
            val builtin = resolveBuiltin(value)
            if (builtin == "new") {
                val worktree = local.value.focusedSession?.directory ?: local.value.selectedProject ?: return@mutate
                createSessionAndFocusNow(worktree)
                return@mutate
            }
            val scope = scope ?: return@mutate
            val focused = local.value.focusedSession ?: run {
                val worktree = local.value.selectedProject ?: return@mutate
                createSessionForMessage(worktree, value) ?: return@mutate
            }
            val command = resolveCommand(value)
            if (command != null) {
                scope.launch(ioLane) {
                    val result = runCatching {
                        msg.sendCommand(focused.id, focused.directory, command.first.name, command.second, agent)
                    }
                    result.onSuccess {
                        mutate {
                            scheduleSync(focused.id, force = true)
                        }
                    }
                    result.onFailure {
                        mutate {
                            local.value = local.value.copy(message = it.message ?: "Failed to run command")
                        }
                    }
                }
                return@mutate
            }
            val key = focusedKey ?: keyForSession(focused.id) ?: key(conn.endpoint.value, focused.id)
            if (active[key] == null) {
                active[key] = focused
                focusedKey = key
            }
            val now = System.currentTimeMillis()
            val id = "local-$now"
            val sort = sequence()
            pending.add(
                key,
                MessageState(
                    id = id,
                    role = "user",
                    text = value,
                    sort = sort,
                    createdAt = now,
                ),
                OptimisticKeepPasses,
            )
            local.value = local.value.copy(
                focusedMessages = local.value.focusedMessages + MessageState(
                    id = id,
                    role = "user",
                    text = value,
                    sort = sort,
                    createdAt = now,
                )
            )
            scope.launch(ioLane) {
                    val result = runCatching {
                        msg.sendMessage(focused.id, focused.directory, value, agent)
                    }
                result.onSuccess {
                    mutate {
                        scheduleSync(focused.id, force = true)
                    }
                }
                result.onFailure {
                    mutate {
                        pending.remove(key, id)
                        if (focusedKey == key) observeFocused()
                        local.value = local.value.copy(message = it.message ?: "Failed to send message")
                    }
                }
            }
        }
    }

    private fun resolveBuiltin(value: String): String? {
        if (!value.startsWith("/")) return null
        val parts = value.split(Regex("\\s+"), limit = 2)
        val name = parts.firstOrNull()?.removePrefix("/")?.trim().orEmpty()
        if (name.equals("new", true)) return "new"
        return null
    }

    private fun resolveCommand(value: String): Pair<CommandState, String>? {
        if (!value.startsWith("/")) return null
        val parts = value.split(Regex("\\s+"), limit = 2)
        val name = parts.firstOrNull()?.removePrefix("/")?.trim().orEmpty()
        if (name.isBlank()) return null
        val match = local.value.commands.firstOrNull { it.name == name } ?: return null
        val args = if (parts.size > 1) parts[1].trim() else ""
        return match to args
    }

    override fun loadMoreMessages() {
        mutate {
            val focused = local.value.focusedSession ?: return@mutate
            val key = focusedKey ?: keyForSession(focused.id) ?: return@mutate
            val limit = messageLimit[key] ?: MessageSyncLimit
            messageLimit[key] = limit + MessageSyncLimit
            syncRemote(focused, more = true)
        }
    }

    override fun archiveSession(session: SessionState) {
        mutate {
            val id = session.id.trim()
            if (id.isBlank()) return@mutate
            val directory = workspaceId(session.directory)
            val worktree = projectForDirectory(directory) ?: directory
            removeSession(id)
            val scope = scope ?: return@mutate
            scope.launch(ioLane) {
                val result = runCatching { proj.archiveSession(id, directory) }
                result.onFailure {
                    mutate {
                        local.value = local.value.copy(message = it.message ?: "Failed to archive session")
                        loadSessions(worktree)
                    }
                }
            }
        }
    }

    override fun renameSession(session: SessionState, title: String) {
        mutate {
            val id = session.id.trim()
            val next = title.trim()
            if (id.isBlank() || next.isBlank()) return@mutate
            val current = sessionById(id) ?: session
            val previous = current.title
            if (previous == next) return@mutate
            applySessionRename(id, next)
            persistSession(current.copy(title = next))
            val directory = workspaceId(session.directory)
            val worktree = projectForDirectory(directory) ?: directory
            val scope = scope ?: return@mutate
            scope.launch(ioLane) {
                val result = runCatching { proj.renameSession(id, directory, next) }
                result.onFailure {
                    mutate {
                        applySessionRename(id, previous)
                        persistSession(current.copy(title = previous))
                        local.value = local.value.copy(message = it.message ?: "Failed to rename session")
                        loadSessions(worktree)
                    }
                }
            }
        }
    }

    fun focusSession(sessionId: String) {
        mutate {
            val entry = active.entries.find { it.value.id == sessionId } ?: return@mutate
            markActivated(sessionId)
            focusedKey = entry.key
            local.value = local.value.copy(
                focusedSession = entry.value,
                quickUnread = local.value.quickUnread - sessionId,
                focusedMessages = emptyList(),
                canLoadMoreMessages = false,
                loadingMoreMessages = false,
            )
            observeFocused()
            syncRemote(entry.value, force = true)
        }
    }

    private fun upsertActiveSession(session: SessionState, project: String?, persist: Boolean = true): String {
        val server = if (manual) input.value else conn.endpoint.value
        val key = key(server, session.id)
        active[key] = session
        sessionProject[key] = project
        messageLimit[key] = messageLimit[key] ?: MessageSyncLimit
        val current = policy[session.id] ?: SyncPolicy()
        policy[session.id] = current.copy(
            lastUpdatedAt = current.lastUpdatedAt ?: session.updatedAt,
        )
        if (persist) {
            scope?.launch(ioLane) {
                cache.upsertSession(server, sessionProject[key], session)
            }
        }
        return key
    }

    private fun focusSession(session: SessionState, project: String?) {
        val key = upsertActiveSession(session, project)
        markActivated(session.id)
        val previous = focusedKey
        if (previous != null && previous != key) {
            flush.remove(previous)?.cancel()
            val sessionId = previous.substringAfter("::")
            scope?.launch(ioLane) {
                flushStaged(previous, sessionId)
            }
        }
        focusedKey = key
        local.value = local.value.copy(
            focusedSession = session,
            activeSessions = active.values.sortedByDescending { it.id },
            quickUnread = local.value.quickUnread - session.id,
            focusedMessages = emptyList(),
            canLoadMoreMessages = false,
            loadingMoreMessages = false,
        )
        if (!project.isNullOrBlank()) {
            loadCommands(project)
        }
        observeFocused()
        syncRemote(session, force = true)
    }

    private fun syncRemote(session: SessionState, more: Boolean = false, force: Boolean = false) {
        if (conn.status.value !is ConnectionState.Connected) return
        val scope = scope ?: return
        scope.launch(mutationLane) {
            val gate = if (more) {
                SyncGate(fetch = true, reason = "more")
            } else {
                syncGate(session, force)
            }
            if (!gate.fetch) {
                debug(
                    unit = LogUnit.sync,
                    event = "sync_skip",
                    message = "Skipping sync",
                    context = mapOf("session" to session.id, "reason" to gate.reason),
                )
                return@launch
            }
            if (!beginSync(session.id)) {
                debug(
                    unit = LogUnit.sync,
                    event = "sync_skip",
                    message = "Skipping sync",
                    context = mapOf("session" to session.id, "reason" to "already_active"),
                )
                return@launch
            }
            try {
                timedSuspend(LaneMutation, "sync.remote", "session=${session.id} more=$more") {
                    val started = System.currentTimeMillis()
                    if (more) {
                        local.value = local.value.copy(loadingMoreMessages = true, message = null)
                    }
                    val key = keyForSession(session.id)
                    val limit = key?.let { messageLimit[it] } ?: MessageSyncLimit
                    val result = timedSuspend(LaneIo, "sync.fetch", "session=${session.id} limit=$limit") {
                        withContext(ioLane) {
                            runCatching {
                                withTimeout(SyncFetchTimeoutMs) {
                                    msg.messages(session.id, session.directory, limit)
                                }
                            }
                        }
                    }
                    if (more) {
                        local.value = local.value.copy(loadingMoreMessages = false)
                    }
                    result.onSuccess { list ->
                        val key = keyForSession(session.id)
                            ?: upsertActiveSession(session, projectForDirectory(session.directory), persist = false)
                        val server = key.substringBefore("::")
                        val now = System.currentTimeMillis()
                        val next = list
                        val complete = next.size < (messageLimit[key] ?: MessageSyncLimit)
                        val cached = timedSuspend(LaneIo, "sync.cache_read", "session=${session.id}") {
                            withContext(ioLane) { cache.listMessages(server, session.id) }
                        }
                        val sticky = stickySort[key]
                        val incoming = mutableListOf<IncomingMessage>()

                        next.forEach {
                            val id = requireNotNull(it.id)
                            val message = messageKey(key, id)
                            val messageRole = it.role
                            val parsed = parser.parseParts(it.parts)
                            synchronized(mapLock) {
                                if (parsed.isEmpty()) {
                                    part.remove(message)
                                } else {
                                    val map = linkedMapOf<String, MessagePart>()
                                    parsed.forEach { item ->
                                        map[item.id] = item
                                    }
                                    part[message] = map
                                }
                            }
                            val messageText = if (messageRole == "assistant") {
                                decorator.render(synchronized(mapLock) { part[message]?.values })
                            } else {
                                it.text
                            }
                            role[message] = messageRole
                            incoming.add(
                                IncomingMessage(
                                    id = id,
                                    role = messageRole,
                                    text = messageText,
                                    createdAt = it.createdAt,
                                    completedAt = it.completedAt,
                                ),
                            )
                        }
                        val plan = timed(
                            LaneMutation,
                            "sync.plan",
                            "session=${session.id} incoming=${incoming.size} cached=${cached.size}",
                        ) {
                            planner.plan(
                                incoming = incoming,
                                cached = cached,
                                sticky = sticky,
                                remoteSort = ::remoteSort,
                                knownSort = { order[messageKey(key, it)] },
                                claimPendingSort = { claimPendingSort(key, it) },
                                retainRemoved = { retainPass.consume(key, it) },
                                complete = complete,
                            )
                        }

                        synchronized(mapLock) {
                            plan.sorts.forEach { (id, sort) ->
                                order[messageKey(key, id)] = sort
                            }
                        }

                        timedSuspend(LaneIo, "sync.cache_upsert", "session=${session.id} count=${plan.upserts.size}") {
                            withContext(ioLane) {
                                plan.upserts.forEach {
                                    cache.upsertMessage(
                                        server,
                                        session.id,
                                        it,
                                        now,
                                    )
                                }
                            }
                        }

                        if (complete) {
                            synchronized(mapLock) {
                                plan.removedIds.forEach { id ->
                                    role.remove(messageKey(key, id))
                                    part.remove(messageKey(key, id))
                                    order.remove(messageKey(key, id))
                                }
                            }
                            timedSuspend(LaneIo, "sync.cache_delete", "session=${session.id} count=${plan.removedIds.size}") {
                                withContext(ioLane) {
                                    plan.removedIds.forEach {
                                        cache.deleteMessage(server, session.id, it)
                                    }
                                }
                            }
                        }

                        clearOverlay(key)
                        trimPending(key)
                        markSync(session.id)
                        syncToolState(session.id, key)
                        if (focusedKey == key) {
                            if (plan.claimed) {
                                observeFocused()
                            }
                            local.value = local.value.copy(canLoadMoreMessages = !complete)
                        }
                        debug(
                            unit = LogUnit.sync,
                            event = "sync_ok",
                            message = "Sync finished",
                            context = mapOf(
                                "session" to session.id,
                                "messages" to "${next.size}",
                                "dt_ms" to "${System.currentTimeMillis() - started}",
                            ),
                        )
                    }
                    result.onFailure {
                        warn(
                            unit = LogUnit.sync,
                            event = "sync_failed",
                            message = "Sync failed",
                            context = mapOf("session" to session.id, "reason" to (it.message ?: "unknown")),
                            error = it,
                        )
                        local.value = local.value.copy(message = it.message ?: "Failed to load messages")
                    }
                }
            } finally {
                endSync(session.id)
            }
        }
    }

    private fun loadSessions(worktree: String) {
        val scope = scope ?: return
        scope.launch(mutationLane) {
            local.value = local.value.copy(loadingSessions = true, message = null)
            var partialFailure = false
            val result = runCatching {
                collectProjectSessions(worktree, SessionFetchLimit) { directory, error ->
                    partialFailure = true
                    warn(
                        unit = LogUnit.workspace,
                        event = "sessions_failed",
                        message = "Workspace sessions failed",
                        context = mapOf("worktree" to directory, "reason" to (error.message ?: "unknown")),
                        error = error,
                    )
                }
            }
            local.value = local.value.copy(loadingSessions = false)
            result.onSuccess { list ->
                resetProjectBackoff(worktree, list)
                runCatching {
                    persistProjectSessionCache(worktree, list)
                }.onFailure {
                    warn(
                        unit = LogUnit.cache,
                        event = "session_cache_update_failed",
                        message = "Session cache update failed",
                        context = mapOf("worktree" to worktree, "reason" to (it.message ?: "unknown")),
                        error = it,
                    )
                }
                local.value = local.value.copy(
                    sessions = limitSessionsPerWorkspace(list, WorkspaceSessionDisplayLimit),
                    message = if (partialFailure) "Some workspaces failed to load sessions" else null,
                )
            }
            result.onFailure {
                local.value = local.value.copy(
                    sessions = emptyList(),
                    message = it.message ?: "Failed to load sessions",
                )
            }
        }
    }

    override suspend fun sessionsForProject(worktree: String, limit: Int?): List<SessionState> {
        return mutateAwait {
            sessionsForProjectNow(worktree, limit)
        }
    }

    override suspend fun cachedSessionsForProject(worktree: String, limit: Int?): List<SessionState> {
        return mutateAwait {
            withContext(ioLane) {
                cache.listProjectSessions(
                    server = serverForCache(),
                    project = workspaceId(worktree),
                    limit = limit,
                )
            }
        }
    }

    private suspend fun sessionsForProjectNow(worktree: String, limit: Int? = null): List<SessionState> {
        val sessions = collectProjectSessions(worktree, limit) { _, _ -> }
            .groupBy { it.id }
            .mapNotNull {
                it.value.maxWithOrNull(compareBy<SessionState>({ value -> value.updatedAt ?: 0L }, { value -> value.id }))
            }
            .sortedWith(
                compareByDescending<SessionState> { it.updatedAt ?: 0L }
                    .thenByDescending { it.id }
            )

        runCatching {
            persistProjectSessionCache(worktree, sessions)
        }.onFailure {
            warn(
                unit = LogUnit.cache,
                event = "session_cache_update_failed",
                message = "Session cache update failed",
                context = mapOf("worktree" to worktree, "reason" to (it.message ?: "unknown")),
                error = it,
            )
        }

        return sessions
    }

    private suspend fun persistProjectSessionCache(worktree: String, sessions: List<SessionState>) {
        val server = serverForCache()
        val project = workspaceId(worktree)
        cache.syncProjectSessions(server, project, sessions)
    }

    private suspend fun collectProjectSessions(
        worktree: String,
        limit: Int?,
        onFailure: (String, Throwable) -> Unit,
    ): List<SessionState> {
        val directories = workspaceDirectoriesForProject(worktree)
        return directories
            .flatMap { directory ->
                runCatching { withContext(ioLane) { proj.sessions(directory, limit) } }
                    .onFailure { onFailure(directory, it) }
                    .getOrDefault(emptyList())
                    .map { mapSessionSummary(it, directory) }
            }
            .filter { it.archivedAt == null && it.parentId == null }
    }

    private fun workspaceDirectoriesForProject(worktree: String): List<String> {
        val selected = workspaceId(worktree)
        val project = local.value.projects.firstOrNull { workspaceId(it.worktree) == selected }
        if (project == null) {
            return listOf(selected)
        }
        return (listOf(project.worktree) + project.sandboxes)
            .map(::workspaceId)
            .distinct()
    }

    private fun projectForDirectory(directory: String): String? {
        val value = workspaceId(directory)
        return local.value.projects
            .firstOrNull {
                workspaceId(it.worktree) == value || it.sandboxes.any { item -> workspaceId(item) == value }
            }
            ?.worktree
    }

    private fun mapSessionSummary(value: SessionSummary, directory: String): SessionState {
        return SessionState(
            id = value.id,
            title = value.title,
            version = value.version,
            directory = workspaceId(if (value.directory.isBlank()) directory else value.directory),
            parentId = value.parentId,
            updatedAt = value.updatedAt,
            archivedAt = value.archivedAt,
        )
    }

    private fun limitSessionsPerWorkspace(sessions: List<SessionState>, limit: Int): List<SessionState> {
        return sessions
            .filter { it.archivedAt == null }
            .groupBy { workspaceId(it.directory) }
            .values
            .flatMap {
                it.sortedWith(
                    compareByDescending<SessionState> { item -> item.updatedAt ?: 0L }
                        .thenByDescending { item -> item.id }
                ).take(limit)
            }
            .sortedWith(
                compareByDescending<SessionState> { it.updatedAt ?: 0L }
                    .thenByDescending { it.id }
            )
    }

    private fun workspaceId(path: String): String {
        val value = path.trimEnd('/', '\\')
        if (value.isBlank()) return path
        return value
    }

    private fun loadCommands(worktree: String) {
        val scope = scope ?: return
        scope.launch(mutationLane) {
            val result = runCatching { withContext(ioLane) { cmd.commands(worktree) } }
            result.onSuccess { list ->
                local.value = local.value.copy(commands = list)
            }
            result.onFailure {
                warn(
                    unit = LogUnit.workspace,
                    event = "commands_failed",
                    message = "Command list failed",
                    context = mapOf("worktree" to worktree, "reason" to (it.message ?: "unknown")),
                    error = it,
                )
            }
        }
    }

    private fun preloadFavoriteSessions(projects: List<ProjectState>) {
        val favorites = projects.filter { it.favorite }
        if (favorites.isEmpty()) return
        val scope = scope ?: return
        scope.launch(mutationLane) {
            favorites.forEach { project ->
                val result = runCatching { sessionsForProjectNow(project.worktree, FavoritePreloadLimit) }
                result.onFailure {
                    warn(
                        unit = LogUnit.workspace,
                        event = "favorite_preload_failed",
                        message = "Favorite preload failed",
                        context = mapOf("worktree" to project.worktree, "reason" to (it.message ?: "unknown")),
                        error = it,
                    )
                }
                result
                    .getOrDefault(emptyList())
                    .forEach {
                        upsertActiveSession(it, project.worktree, persist = false)
                    }
            }
            local.value = local.value.copy(activeSessions = active.values.sortedByDescending { it.id })
        }
    }

    private suspend fun hydrateLastNow() {
        val recent = withContext(ioLane) { runCatching { cache.recentSession() }.getOrNull() } ?: return
        val scope = scope ?: return
        scope.launch(mutationLane) {
            manual = true
            input.value = recent.server
            conn.setUrl(recent.server)
            conn.refresh(false)
            manual = false
            focusSession(recent.session, recent.project)
        }
    }

    private fun stream(scope: CoroutineScope) {
        stream?.cancel()
        stream = streamer.start(scope, ::onEvent)
    }

    private fun reconcile(scope: CoroutineScope) {
        reconcile?.cancel()
        reconcile = reconciler.start(scope) {
            mutate {
                if (conn.status.value !is ConnectionState.Connected) {
                    conn.refresh(false)
                }
                syncTrackedSessions()
            }
        }
    }

    private suspend fun syncTrackedSessions() {
        discoverFavoriteRunningSessions()
        syncTargets().forEach(::syncRemote)
    }

    private suspend fun discoverFavoriteRunningSessions() {
        val favorites = local.value.projects.filter { it.favorite }
        favorites
            .flatMap { workspaceDirectoriesForProject(it.worktree) }
            .distinct()
            .forEach { directory ->
                if (!shouldCheckDirectory(directory)) return@forEach
                val result = runCatching {
                    withTimeout(SyncFetchTimeoutMs) {
                        withContext(ioLane) {
                            msg.status(directory)
                        }
                    }
                }
                result.onFailure {
                    if (!unsupportedCheck(it)) {
                        return@onFailure
                    }
                    val current = directoryPolicy[directory] ?: DirectoryPolicy()
                    directoryPolicy[directory] = current.copy(
                        checks = false,
                        interval = nextInterval(current.interval),
                        lastCheckAt = System.currentTimeMillis(),
                    )
                }
                val status = result.getOrNull() ?: return@forEach
                val running = status
                    .filterValues { it == "busy" || it == "retry" }
                    .keys
                val tracked = active.values
                    .filter { workspaceId(it.directory) == workspaceId(directory) }
                    .map { it.id }
                    .toSet()
                markDirectoryChecked(directory, running.isNotEmpty())
                tracked
                    .filterNot(running::contains)
                    .forEach {
                        setProcessing(it, false)
                    }
                running.forEach { sessionId ->
                    if (keyForSession(sessionId) == null) {
                        ensureSessionTracked(sessionId, directory)
                        resolveSession(sessionId, directory)
                    }
                    setProcessing(sessionId, true)
                    scheduleSync(sessionId, force = true)
                }
            }
    }

    private fun syncTargets(): List<SessionState> {
        val current = local.value
        val focused = current.focusedSession
        val pinned = pinnedSessionsForFavoriteProjects(
            sessions = active.values.toList(),
            projects = current.projects,
            include = current.quickPinInclude,
            exclude = current.quickPinExclude,
        )
        if (focused == null) return pinned
        return (listOf(focused) + pinned).distinctBy { it.id }
    }

    private fun pinnedSessionsForFavoriteProjects(
        sessions: List<SessionState>,
        projects: List<ProjectState>,
        include: Set<String>,
        exclude: Set<String>,
    ): List<SessionState> {
        val favorites = projects.filter { it.favorite }
        if (favorites.isEmpty()) return emptyList()
        val cutoff = System.currentTimeMillis() - SyncPinnedWindowMs
        return favorites
            .flatMap { project ->
                val dirs = (listOf(project.worktree) + project.sandboxes)
                    .map(::workspaceId)
                    .toSet()
                val rows = sessions
                    .filter { it.archivedAt == null && dirs.contains(workspaceId(it.directory)) }
                    .groupBy { it.id }
                    .mapNotNull {
                        it.value.maxWithOrNull(compareBy<SessionState>({ value -> value.updatedAt ?: 0L }, { value -> value.id }))
                    }
                    .sortedWith(
                        compareByDescending<SessionState> { it.updatedAt ?: 0L }
                            .thenByDescending { it.id }
                    )
                val system = favoriteSystemPins(rows, cutoff)
                val forced = rows.filter { include.contains(it.id) }
                (system.filterNot { exclude.contains(it.id) } + forced)
                    .distinctBy { it.id }
            }
            .distinctBy { it.id }
    }

    private fun favoriteSystemPins(sessions: List<SessionState>, cutoff: Long): List<SessionState> {
        val recent = sessions.filter { (it.updatedAt ?: 0L) >= cutoff }
        if (recent.isNotEmpty()) return recent
        val latest = sessions
            .maxWithOrNull(compareBy<SessionState>({ it.updatedAt ?: 0L }, { it.id }))
            ?: return emptyList()
        val updatedAt = latest.updatedAt ?: return listOf(latest)
        val window = sessions.filter { (it.updatedAt ?: Long.MIN_VALUE) >= updatedAt - SyncPinnedFavoriteWindowMs }
        if (window.isEmpty()) return listOf(latest)
        return window
    }

    private suspend fun onEvent(event: SessionStreamEvent) {
        mutate(event.id?.let { "sse:$it" }) {
            timedSuspend(LaneMutation, "sse.event", "type=${event.type}") {
                onEventNow(event)
            }
        }
    }

    private suspend fun onEventNow(event: SessionStreamEvent) {
        when (val action = reducer.reduce(event)) {
            is SessionEventAction.Ignore -> {
                debug(
                    unit = LogUnit.stream,
                    event = "sse_ignore",
                    message = "Ignored SSE action",
                    context = mapOf("type" to action.type),
                )
                return
            }

            is SessionEventAction.ReloadProjects -> {
                markSseApplied(event.type, null)
                loadProjectsNow()
                syncTrackedSessions()
            }

            is SessionEventAction.SessionChanged -> {
                handleSessionChanged(action)
            }

            is SessionEventAction.MessageUpdated -> {
                handleMessageUpdated(action, event.type)
            }

            is SessionEventAction.MessageRemoved -> {
                handleMessageRemoved(action, event.type)
            }

            is SessionEventAction.MessagePartUpdated -> {
                handleMessagePartUpdated(action, event.type)
            }

            is SessionEventAction.MessagePartRemoved -> {
                handleMessagePartRemoved(action, event.type)
            }

            is SessionEventAction.SessionStatus -> {
                handleSessionStatus(action, event.type)
            }

            is SessionEventAction.SessionDiff -> {
                handleSessionDiff(action, event.type)
            }

            is SessionEventAction.Drop -> {
                markSseDropped(action.type, action.reason)
            }
        }
    }

    private fun handleSessionChanged(action: SessionEventAction.SessionChanged) {
        markSseApplied(action.type, null)
        val selected = local.value.selectedProject
        if (!selected.isNullOrBlank() && workspaceDirectoriesForProject(selected).contains(workspaceId(action.directory))) {
            loadSessions(selected)
        }
        action.deletedSessionId?.let(::removeSession)
    }

    private fun handleMessageUpdated(action: SessionEventAction.MessageUpdated, type: String) {
        var key = keyForSession(action.sessionId)
        if (key == null) {
            markSseDropped(type, "session not focused/active id=${action.sessionId}")
            ensureSessionTracked(action.sessionId, action.directory)
            resolveSession(action.sessionId, action.directory)
            key = keyForSession(action.sessionId) ?: return
        }
        val message = messageKey(key, action.messageId)
        if (action.role == "user") {
            pending.claim(key, action.text ?: "")?.let {
                stickySort.getOrPut(key) { linkedMapOf() }[action.messageId] = it.sort
            }
        }
        role[message] = action.role
        retainPass.set(key, action.messageId, ReconcileKeepPasses)
        val sort = stickySort[key]?.remove(action.messageId) ?: order[message] ?: sequence()
        stageMessage(
            key,
            action.sessionId,
            action.messageId,
            action.role,
            if (!action.text.isNullOrBlank()) action.text else decorator.render(part[message]?.values),
            sort,
            action.createdAt,
            action.completedAt,
        )
        markSseApplied(type, action.sessionId)
        scheduleSync(action.sessionId, true)
    }

    private suspend fun handleMessageRemoved(action: SessionEventAction.MessageRemoved, type: String) {
        var key = keyForSession(action.sessionId)
        if (key == null) {
            markSseDropped(type, "session not focused/active id=${action.sessionId}")
            ensureSessionTracked(action.sessionId, action.directory)
            resolveSession(action.sessionId, action.directory)
            key = keyForSession(action.sessionId) ?: return
        }
        val server = key.substringBefore("::")
        withContext(ioLane) {
            cache.deleteMessage(server, action.sessionId, action.messageId)
        }
        role.remove(messageKey(key, action.messageId))
        synchronized(mapLock) {
            part.remove(messageKey(key, action.messageId))
            order.remove(messageKey(key, action.messageId))
            overlay.remove(messageKey(key, action.messageId))
            overlayDirty[key]?.remove(messageKey(key, action.messageId))
        }
        if (focusedKey == key) {
            publishFocused(key)
        }
        markSseApplied(type, action.sessionId)
    }

    private fun handleMessagePartUpdated(action: SessionEventAction.MessagePartUpdated, type: String) {
        val next = parser.parsePart(action.part)
        if (next == null) {
            markSseDropped(type, "missing part type")
            return
        }
        var key = keyForSession(action.sessionId)
        if (key == null) {
            markSseDropped(type, "session not focused/active id=${action.sessionId}")
            ensureSessionTracked(action.sessionId, action.directory)
            resolveSession(action.sessionId, action.directory)
            key = keyForSession(action.sessionId) ?: return
        }
        val message = messageKey(key, action.messageId)
        val updated = synchronized(mapLock) {
            LinkedHashMap(part[message] ?: emptyMap<String, MessagePart>()).apply {
                this[next.id] = next
            }
        }
        synchronized(mapLock) {
            part[message] = updated
        }
        retainPass.set(key, action.messageId, ReconcileKeepPasses)
        val sort = synchronized(mapLock) { order[message] } ?: sequence()
        stageMessage(
            key,
            action.sessionId,
            action.messageId,
            role[message] ?: "assistant",
            synchronized(mapLock) { overlay[message]?.text }
                ?: focusedDb.firstOrNull { it.id == action.messageId }?.text
                ?: "(streaming...)",
            sort,
        )
        markSseApplied(type, action.sessionId)
        scheduleSync(action.sessionId, true)
    }

    private fun handleMessagePartRemoved(action: SessionEventAction.MessagePartRemoved, type: String) {
        val entry = active.entries.find { messageKey(it.key, action.messageId).let(part::containsKey) }
        if (entry == null) {
            markSseDropped(type, "message not in active cache")
            return
        }
        val key = entry.key
        val message = messageKey(key, action.messageId)
        val updated = synchronized(mapLock) {
            LinkedHashMap(part[message] ?: return).apply {
                remove(action.partId)
            }
        }
        synchronized(mapLock) {
            if (updated.isEmpty()) {
                part.remove(message)
            }
            if (updated.isNotEmpty()) part[message] = updated
        }
        val sessionId = key.substringAfter("::")
        val sort = synchronized(mapLock) { order[message] } ?: sequence()
        stageMessage(
            key,
            sessionId,
            action.messageId,
            role[message] ?: "assistant",
            synchronized(mapLock) { overlay[message]?.text }
                ?: focusedDb.firstOrNull { it.id == action.messageId }?.text
                ?: "(streaming...)",
            sort,
        )
        markSseApplied(type, sessionId)
        scheduleSync(sessionId, true)
    }

    private fun handleSessionStatus(action: SessionEventAction.SessionStatus, type: String) {
        if (keyForSession(action.sessionId) == null) {
            ensureSessionTracked(action.sessionId, action.directory)
            resolveSession(action.sessionId, action.directory)
        }
        markSseApplied(type, action.sessionId)
        setProcessing(action.sessionId, action.status != "idle")
        scheduleSync(action.sessionId)
    }

    private fun handleSessionDiff(action: SessionEventAction.SessionDiff, type: String) {
        if (keyForSession(action.sessionId) == null) {
            ensureSessionTracked(action.sessionId, action.directory)
            resolveSession(action.sessionId, action.directory)
        }
        markSseApplied(type, action.sessionId)
    }

    private fun ensureSessionTracked(sessionId: String, directory: String?) {
        if (keyForSession(sessionId) != null) return
        val worktree = if (!directory.isNullOrBlank() && directory != "global") {
            workspaceId(directory)
        } else {
            null
        }
        if (worktree == null) return
        val now = System.currentTimeMillis()
        upsertActiveSession(
            session = SessionState(
                id = sessionId,
                title = "Session ${sessionId.take(8)}",
                version = "",
                directory = worktree,
                updatedAt = now,
            ),
            project = worktree,
        )
        local.value = local.value.copy(activeSessions = active.values.sortedByDescending { it.id })
    }

    private fun scheduleSync(sessionId: String, burst: Boolean = false, force: Boolean = false) {
        val scope = scope ?: return
        sync.schedule(scope, sessionId, if (burst) SyncBurstDelayMs else SyncDelayMs) {
            val entry = active.values.find { value -> value.id == sessionId }
                ?: local.value.focusedSession?.takeIf { it.id == sessionId }
            if (entry != null) {
                syncRemote(entry, force = force)
            }
        }
    }

    private fun resolveSession(sessionId: String, directory: String?) {
        val scope = scope ?: return
        if (!resolver.allow(sessionId)) return
        scope.launch(mutationLane) {
            val worktree = if (!directory.isNullOrBlank() && directory != "global") {
                directory
            } else {
                local.value.selectedProject
            }
            if (worktree.isNullOrBlank()) return@launch
            val result = runCatching { withContext(ioLane) { proj.sessions(worktree) } }
            result.onFailure {
                warn(
                    unit = LogUnit.workspace,
                    event = "resolve_session_failed",
                    message = "Session resolve failed",
                    context = mapOf("session" to sessionId, "reason" to (it.message ?: "unknown")),
                    error = it,
                )
            }
            val found = result.getOrNull()
                ?.firstOrNull { it.id == sessionId }
                ?: return@launch
            val session = SessionState(
                id = found.id,
                title = found.title,
                version = found.version,
                directory = found.directory,
                parentId = found.parentId,
                updatedAt = found.updatedAt,
                archivedAt = found.archivedAt,
            )
            debug(
                unit = LogUnit.workspace,
                event = "resolve_session",
                message = "Session resolved",
                context = mapOf("session" to sessionId, "title" to session.title),
            )
            upsertActiveSession(session, worktree)
            local.value = local.value.copy(activeSessions = active.values.sortedByDescending { it.id })
            scheduleSync(sessionId)
        }
    }

    private fun stageMessage(
        key: String,
        sessionId: String,
        messageId: String,
        role: String,
        text: String,
        sort: String,
        createdAt: Long? = null,
        completedAt: Long? = null,
    ) {
        val message = messageKey(key, messageId)
        synchronized(mapLock) {
            order[message] = sort
            val current = overlay[message] ?: focusedDb.firstOrNull { it.id == messageId }
            overlay[message] = MessageState(
                id = messageId,
                role = role,
                text = text,
                sort = sort,
                createdAt = createdAt ?: current?.createdAt,
                completedAt = completedAt ?: current?.completedAt,
            )
            overlayDirty.getOrPut(key) { linkedSetOf() }.add(message)
        }
        if (focusedKey == key) {
            publishFocused(key)
        }
        queueFlush(key, sessionId)
    }

    private fun queueFlush(key: String, sessionId: String) {
        val scope = scope ?: return
        flush[key]?.cancel()
        flush[key] = scope.launch(mutationLane) {
            delay(OverlayFlushDelayMs)
            flushStaged(key, sessionId)
        }
    }

    private suspend fun flushStaged(key: String, sessionId: String) {
        val staged = synchronized(mapLock) { overlayDirty.remove(key)?.toList() } ?: return
        val server = key.substringBefore("::")
        val now = System.currentTimeMillis()
        withContext(ioLane) {
            staged.forEach {
                val message = synchronized(mapLock) { overlay[it] } ?: return@forEach
                cache.upsertMessage(
                    server,
                    sessionId,
                    message,
                    now,
                )
            }
        }
    }

    private fun clearOverlay(key: String) {
        synchronized(mapLock) {
            overlay.keys
                .filter { it.startsWith("$key::") }
                .forEach(overlay::remove)
            overlayDirty.remove(key)
        }
        if (focusedKey == key) {
            publishFocused(key)
        }
    }

    private fun observeFocused() {
        observe?.cancel()
        val key = focusedKey ?: return
        focusedDb = emptyList()
        val server = key.substringBefore("::")
        val session = key.substringAfter("::")
        observe = (scope ?: return).launch(mutationLane) {
            cache.observeMessages(server, session)
                .collect {
                    timed(LaneMutation, "focused.observe", "session=$session size=${it.size}") {
                        it.forEach { message ->
                            order[messageKey(key, message.id)] = message.sort
                        }
                        focusedDb = it
                        publishFocused(key)
                    }
                }
        }
    }

    private fun publishFocused(key: String) {
        if (focusedKey != key) return
        val scope = scope ?: return
        val prefix = "$key::"
        val base = focusedDb
        val snapshot = synchronized(mapLock) {
            Triple(
                overlay.filterKeys { it.startsWith(prefix) },
                pending.list(key),
                part.filterKeys { it.startsWith(prefix) },
            )
        }
        val staged = snapshot.first
        val queued = snapshot.second
        val parts = snapshot.third
        val token = ++publishToken
        publish?.cancel()
        publish = scope.launch(cpuLane) {
            val next = timed(LaneCpu, "focused.project", "session=$key base=${base.size} staged=${staged.size}") {
                projector.project(key, base, staged, queued, parts)
            }
            withContext(mutationLane) {
                if (focusedKey != key || token != publishToken) return@withContext
                local.value = local.value.copy(focusedMessages = next)
            }
        }
    }

    private fun keyForSession(sessionId: String): String? {
        return active.keys.find { it.endsWith("::$sessionId") }
    }

    private fun messageKey(key: String, messageId: String): String {
        return "$key::$messageId"
    }

    private fun sequence(): String {
        return "z-${seq.incrementAndGet().toString().padStart(20, '0')}"
    }

    private fun remoteSort(index: Int): String {
        return "r-${index.toString().padStart(20, '0')}"
    }

    private fun claimPendingSort(key: String, text: String): String? {
        return pending.claim(key, text)?.sort
    }

    private fun trimPending(key: String) {
        if (pending.trim(key)) {
            if (focusedKey == key) {
                observeFocused()
            }
        }
    }

    private fun removeSession(sessionId: String) {
        policy.remove(sessionId)
        clearSessionQuickPin(sessionId)
        setProcessing(sessionId, false)
        local.value = local.value.copy(
            quickUnread = local.value.quickUnread - sessionId,
            sessions = local.value.sessions.filterNot { it.id == sessionId },
        )
        val entry = active.entries.find { it.value.id == sessionId }
        val server = entry?.key?.substringBefore("::") ?: serverForCache()
        scope?.launch(ioLane) {
            cache.deleteSession(server, sessionId)
            cache.deleteSessionMessages(server, sessionId)
        }
        if (entry == null) return
        val key = entry.key
        active.remove(key)
        sessionProject.remove(key)
        pending.clear(key)
        retainPass.clear(key)
        stickySort.remove(key)
        order.keys
            .filter { it.startsWith("$key::") }
            .forEach(order::remove)
        clearOverlay(key)
        flush.remove(key)?.cancel()
        messageLimit.remove(key)
        if (focusedKey == key) {
            focusedKey = active.keys.firstOrNull()
            local.value = local.value.copy(
                focusedSession = focusedKey?.let(active::get),
                activeSessions = active.values.sortedByDescending { it.id },
                canLoadMoreMessages = false,
                loadingMoreMessages = false,
            )
            observeFocused()
            return
        }
        local.value = local.value.copy(activeSessions = active.values.sortedByDescending { it.id })
    }

    private fun sessionById(sessionId: String): SessionState? {
        return local.value.focusedSession?.takeIf { it.id == sessionId }
            ?: local.value.sessions.firstOrNull { it.id == sessionId }
            ?: active.values.firstOrNull { it.id == sessionId }
    }

    private fun persistSession(session: SessionState) {
        val scope = scope ?: return
        val server = serverForCache()
        val project = projectForDirectory(session.directory)
        scope.launch(ioLane) {
            cache.upsertSession(server, project, session)
        }
    }

    private fun applySessionRename(sessionId: String, title: String) {
        active.entries
            .filter { it.value.id == sessionId }
            .forEach {
                active[it.key] = it.value.copy(title = title)
            }
        val current = local.value
        local.value = current.copy(
            focusedSession = current.focusedSession
                ?.takeIf { it.id == sessionId }
                ?.copy(title = title)
                ?: current.focusedSession,
            sessions = current.sessions.map {
                if (it.id == sessionId) {
                    it.copy(title = title)
                } else {
                    it
                }
            },
            activeSessions = active.values.sortedByDescending { it.id },
        )
        val scope = scope ?: return
        active.entries
            .filter { it.value.id == sessionId }
            .forEach {
                val project = sessionProject[it.key]
                val session = it.value
                val server = it.key.substringBefore("::")
                scope.launch(ioLane) {
                    cache.upsertSession(server, project, session)
                }
            }
    }

    private fun setProcessing(sessionId: String, running: Boolean) {
        val current = local.value
        val processing = if (running) {
            current.quickProcessing + sessionId
        } else {
            current.quickProcessing - sessionId
        }
        val finished = current.quickProcessing.contains(sessionId) && !running
        val focused = current.focusedSession?.id == sessionId
        val unread = if (running || focused) {
            current.quickUnread - sessionId
        } else if (finished) {
            current.quickUnread + sessionId
        } else {
            current.quickUnread
        }
        if (processing == current.quickProcessing && unread == current.quickUnread) return
        local.value = current.copy(
            quickProcessing = processing,
            quickUnread = unread,
        )
    }

    private fun clearSessionQuickPin(sessionId: String) {
        val current = local.value
        if (!current.quickPinInclude.contains(sessionId) && !current.quickPinExclude.contains(sessionId)) return
        val include = current.quickPinInclude - sessionId
        val exclude = current.quickPinExclude - sessionId
        local.value = local.value.copy(
            quickPinInclude = include,
            quickPinExclude = exclude,
        )
        val scope = scope ?: return
        val server = serverForCache()
        scope.launch(ioLane) {
            runCatching {
                cache.setSessionQuickPins(server, include, exclude)
            }.onFailure {
                mutate {
                    local.value = local.value.copy(
                        quickPinInclude = current.quickPinInclude,
                        quickPinExclude = current.quickPinExclude,
                    )
                }
            }
        }
    }

    private fun serverForCache(): String {
        return if (manual) input.value else conn.endpoint.value
    }

    private fun mergeProjects(projects: List<ProjectState>, favorites: Set<String>, hidden: Set<String>): List<ProjectState> {
        val next = canonicalProjects(projects)
            .filterNot { hidden.contains(workspaceId(it.worktree)) }
            .map {
                it.copy(favorite = favorites.contains(workspaceId(it.worktree)))
            }
        val known = next.map { workspaceId(it.worktree) }.toSet()
        val missing = favorites
            .filterNot(hidden::contains)
            .filterNot(known::contains)
            .map {
                ProjectState(
                    id = "favorite:$it",
                    worktree = it,
                    name = projectName(it),
                    favorite = true,
                )
            }

        return sortProjects(next + missing)
    }

    private fun canonicalProjects(projects: List<ProjectState>): List<ProjectState> {
        return projects
            .groupBy { workspaceId(it.worktree) }
            .map { (worktree, value) ->
                val sandboxes = value
                    .flatMap { it.sandboxes }
                    .map(::workspaceId)
                    .filter { it.isNotBlank() && it != worktree }
                    .distinct()
                val fallback = projectName(worktree)
                val name = value
                    .map { it.name.trim() }
                    .firstOrNull { it.isNotBlank() && it != fallback }
                    ?: value
                        .map { it.name.trim() }
                        .firstOrNull { it.isNotBlank() }
                    ?: fallback
                ProjectState(
                    id = value.firstOrNull { it.id.isNotBlank() }?.id ?: "project:$worktree",
                    worktree = worktree,
                    name = name,
                    sandboxes = sandboxes,
                    favorite = value.any { it.favorite },
                )
            }
    }

    private fun sortProjects(projects: List<ProjectState>): List<ProjectState> {
        return projects.sortedWith(
            compareByDescending<ProjectState> { it.favorite }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun projectName(worktree: String): String {
        val value = worktree.trimEnd('/', '\\')
        if (value.isBlank()) return worktree
        val slash = value.lastIndexOf('/')
        val backslash = value.lastIndexOf('\\')
        val index = maxOf(slash, backslash)
        if (index < 0) return value
        val name = value.substring(index + 1)
        if (name.isBlank()) return value
        return name
    }

    private fun generatedSessionTitle(text: String): String {
        val line = text
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            ?: DefaultSessionTitle
        if (line.length <= SessionTitleMaxLength) return line
        return line.take(SessionTitleMaxLength).trimEnd()
    }

    private fun key(server: String, sessionId: String): String {
        return "$server::$sessionId"
    }

    private suspend fun beginSync(sessionId: String): Boolean {
        return sync.begin(sessionId)
    }

    private suspend fun endSync(sessionId: String) {
        sync.end(sessionId)
    }

    private data class SyncGate(
        val fetch: Boolean,
        val reason: String,
    )

    private suspend fun syncGate(session: SessionState, force: Boolean): SyncGate {
        val now = System.currentTimeMillis()
        val current = policy[session.id] ?: SyncPolicy(lastUpdatedAt = session.updatedAt)
        policy[session.id] = current
        if (force) {
            return checkFirst(session, current, now, force = true)
        }
        val due = now - current.lastCheckAt >= current.interval
        if (!due) {
            return SyncGate(fetch = false, reason = "backoff")
        }
        val running = isRunning(session.id, current)
        if (running && now - current.lastSseAt <= SyncSseQuietWindowMs) {
            return SyncGate(fetch = false, reason = "recent_sse")
        }
        return checkFirst(session, current, now, force = false)
    }

    private suspend fun checkFirst(session: SessionState, current: SyncPolicy, now: Long, force: Boolean): SyncGate {
        val latest = current.copy(lastCheckAt = now)
        policy[session.id] = latest
        if (!latest.checks) {
            policy[session.id] = latest.copy(interval = nextInterval(latest.interval))
            return SyncGate(fetch = true, reason = if (force) "forced_fallback" else "fallback")
        }
        val status = runCatching {
            withTimeout(SyncFetchTimeoutMs) {
                withContext(ioLane) {
                    msg.status(session.directory)
                }
            }
        }
        val withStatus = status.fold(
            onSuccess = {
                val next = it[session.id] ?: "idle"
                setProcessing(session.id, next == "busy" || next == "retry")
                latest.copy(status = next)
            },
            onFailure = {
                if (unsupportedCheck(it)) {
                    latest.copy(checks = false)
                } else {
                    latest
                }
            },
        )
        policy[session.id] = withStatus
        if (!withStatus.checks) {
            policy[session.id] = withStatus.copy(interval = nextInterval(withStatus.interval))
            return SyncGate(fetch = true, reason = if (force) "forced_fallback" else "fallback")
        }
        val updated = runCatching {
            withTimeout(SyncFetchTimeoutMs) {
                withContext(ioLane) {
                    msg.updatedAt(session.id, session.directory)
                }
            }
        }
        return updated.fold(
            onSuccess = { value ->
                val known = withStatus.lastUpdatedAt
                val initial = known == null && withStatus.lastSyncAt == 0L
                val changed = value != null && known != null && value != known
                val discovered = value != null && known == null
                if (initial || changed || discovered) {
                    val next = withStatus.copy(
                        lastUpdatedAt = value ?: known,
                        interval = SyncBackoffBaseMs,
                    )
                    policy[session.id] = next
                    return@fold SyncGate(fetch = true, reason = if (known == null) "initial" else "changed")
                }
                val next = withStatus.copy(interval = nextInterval(withStatus.interval))
                policy[session.id] = next
                SyncGate(fetch = false, reason = "unchanged")
            },
            onFailure = {
                if (unsupportedCheck(it)) {
                    policy[session.id] = withStatus.copy(
                        checks = false,
                        interval = nextInterval(withStatus.interval),
                    )
                    return@fold SyncGate(fetch = true, reason = if (force) "forced_fallback" else "fallback")
                }
                SyncGate(fetch = false, reason = "check_error")
            },
        )
    }

    private fun unsupportedCheck(error: Throwable): Boolean {
        val message = error.message?.lowercase() ?: return false
        return message.contains("404") || message.contains("405")
    }

    private fun nextInterval(current: Long): Long {
        val doubled = current * 2
        if (doubled > SyncBackoffMaxMs) return SyncBackoffMaxMs
        return doubled
    }

    private fun shouldCheckDirectory(directory: String): Boolean {
        val now = System.currentTimeMillis()
        val current = directoryPolicy[directory] ?: DirectoryPolicy()
        directoryPolicy[directory] = current
        if (!current.checks) return false
        return now - current.lastCheckAt >= current.interval
    }

    private fun markDirectoryChecked(directory: String, active: Boolean) {
        val now = System.currentTimeMillis()
        val current = directoryPolicy[directory] ?: DirectoryPolicy()
        directoryPolicy[directory] = current.copy(
            lastCheckAt = now,
            interval = if (active) SyncBackoffBaseMs else nextInterval(current.interval),
        )
    }

    private fun markProjectActivated(worktree: String) {
        workspaceDirectoriesForProject(worktree).forEach(::markDirectoryActivated)
    }

    private fun markDirectoryActivated(directory: String) {
        val current = directoryPolicy[directory] ?: DirectoryPolicy()
        directoryPolicy[directory] = current.copy(
            interval = SyncBackoffBaseMs,
            lastCheckAt = 0L,
            checks = true,
        )
    }

    private fun markActivated(sessionId: String) {
        val now = System.currentTimeMillis()
        val current = policy[sessionId] ?: SyncPolicy()
        policy[sessionId] = current.copy(
            interval = SyncBackoffBaseMs,
            lastCheckAt = 0L,
            activatedAt = now,
        )
        active.values
            .firstOrNull { it.id == sessionId }
            ?.let { markDirectoryActivated(it.directory) }
    }

    private fun resetProjectBackoff(worktree: String, sessions: List<SessionState>) {
        val dirs = workspaceDirectoriesForProject(worktree)
        dirs.forEach(::markDirectoryActivated)
        val now = System.currentTimeMillis()
        sessions
            .filter { dirs.contains(workspaceId(it.directory)) }
            .forEach {
                val current = policy[it.id] ?: SyncPolicy(lastUpdatedAt = it.updatedAt)
                policy[it.id] = current.copy(
                    interval = SyncBackoffBaseMs,
                    lastCheckAt = 0L,
                    activatedAt = now,
                )
            }
    }

    private fun markSync(sessionId: String) {
        val now = System.currentTimeMillis()
        val current = policy[sessionId] ?: SyncPolicy()
        policy[sessionId] = current.copy(
            lastSyncAt = now,
            interval = if (current.checks) SyncBackoffBaseMs else current.interval,
        )
    }

    private fun syncToolState(sessionId: String, key: String) {
        val now = System.currentTimeMillis()
        val tool = synchronized(mapLock) {
            part.entries
                .asSequence()
                .filter { it.key.startsWith("$key::") }
                .flatMap { it.value.values.asSequence() }
                .firstOrNull { it.type == "tool" && it.completedAt == null }
        }
        val current = policy[sessionId] ?: SyncPolicy()
        if (tool == null) {
            policy[sessionId] = current.copy(tool = null, toolAt = 0L)
            return
        }
        val since = if (current.tool == tool.id) {
            if (current.toolAt == 0L) now else current.toolAt
        } else {
            now
        }
        val status = if (now - since >= SyncToolStuckMs) {
            "idle"
        } else {
            current.status
        }
        policy[sessionId] = current.copy(
            tool = tool.id,
            toolAt = since,
            status = status,
        )
    }

    private fun isRunning(sessionId: String, current: SyncPolicy): Boolean {
        if (current.tool != null && current.toolAt > 0L) {
            val now = System.currentTimeMillis()
            return now - current.toolAt < SyncToolStuckMs
        }
        if (local.value.quickProcessing.contains(sessionId)) return true
        if (current.status == "busy" || current.status == "retry") return true
        return false
    }

    private fun markSseApplied(type: String, sessionId: String?) {
        if (sessionId != null) {
            val now = System.currentTimeMillis()
            val current = policy[sessionId] ?: SyncPolicy()
            policy[sessionId] = current.copy(
                lastSseAt = now,
                interval = SyncBackoffBaseMs,
                lastCheckAt = 0L,
            )
        }
        debug(
            unit = LogUnit.stream,
            event = "sse_apply",
            message = "Applied SSE event",
            context = mapOf("type" to type, "session" to (sessionId ?: "")),
        )
    }

    private fun markSseDropped(type: String, reason: String) {
        warn(
            unit = LogUnit.stream,
            event = "sse_drop",
            message = "Dropped SSE event",
            context = mapOf("type" to type, "reason" to reason),
        )
    }

    private inline fun <T> timed(lane: String, path: String, details: String, block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            val dt = (System.nanoTime() - start) / 1_000_000
            debug(
                unit = LogUnit.sync,
                event = "perf",
                message = "Timing marker",
                context = mapOf(
                    "lane" to lane,
                    "path" to path,
                    "dt_ms" to "$dt",
                    "thread" to Thread.currentThread().name,
                    "details" to details,
                ),
            )
        }
    }

    private suspend fun <T> timedSuspend(lane: String, path: String, details: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            val dt = (System.nanoTime() - start) / 1_000_000
            debug(
                unit = LogUnit.sync,
                event = "perf",
                message = "Timing marker",
                context = mapOf(
                    "lane" to lane,
                    "path" to path,
                    "dt_ms" to "$dt",
                    "thread" to Thread.currentThread().name,
                    "details" to details,
                ),
            )
        }
    }

    private fun debug(unit: LogUnit, event: String, message: String, context: Map<String, String> = emptyMap()) {
        log.debug(unit, LogTag, event, message, context)
    }

    private fun warn(
        unit: LogUnit,
        event: String,
        message: String,
        context: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    ) {
        log.warn(unit, LogTag, event, message, context, error)
    }

    fun stop() {
        mutation?.cancel()
        mutation = null
        stream?.cancel()
        stream = null
        reconcile?.cancel()
        reconcile = null
        observe?.cancel()
        observe = null
        publish?.cancel()
        publish = null
        sync.cancelAll()
        flush.values.forEach { it.cancel() }
        flush.clear()
        policy.clear()
        directoryPolicy.clear()
    }
}

internal interface MutationExecutor {
    fun launch(key: String? = null, block: suspend () -> Unit)

    suspend fun <T> run(key: String? = null, block: suspend () -> T): T

    fun cancel()
}

internal class PipelineMutationExecutor(
    private val pipeline: MutationPipeline,
) : MutationExecutor {
    override fun launch(key: String?, block: suspend () -> Unit) {
        pipeline.launch(key, block)
    }

    override suspend fun <T> run(key: String?, block: suspend () -> T): T {
        return pipeline.run(key, block)
    }

    override fun cancel() {
        pipeline.cancel()
    }
}

internal class LegacyMutationExecutor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) : MutationExecutor {
    private val jobs = linkedSetOf<Job>()

    override fun launch(key: String?, block: suspend () -> Unit) {
        val job = scope.launch(dispatcher) {
            block()
        }
        synchronized(jobs) {
            jobs.add(job)
        }
        job.invokeOnCompletion {
            synchronized(jobs) {
                jobs.remove(job)
            }
        }
    }

    override suspend fun <T> run(key: String?, block: suspend () -> T): T {
        return withContext(dispatcher) { block() }
    }

    override fun cancel() {
        synchronized(jobs) {
            jobs.toList()
        }.forEach { it.cancel() }
    }
}

internal class MutationPipeline(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) {
    private data class Entry(
        val key: String?,
        val block: suspend () -> Unit,
    )

    private val queue = Channel<Entry>(Channel.UNLIMITED)
    private val pending = linkedSetOf<String>()
    private val worker = scope.launch(dispatcher) {
        for (entry in queue) {
            runCatching { entry.block() }
            entry.key?.let { key ->
                synchronized(pending) {
                    pending.remove(key)
                }
            }
        }
    }

    fun launch(key: String? = null, block: suspend () -> Unit): Boolean {
        if (key != null) {
            val accepted = synchronized(pending) {
                if (pending.contains(key)) return@synchronized false
                pending.add(key)
                true
            }
            if (!accepted) return false
        }
        val sent = queue.trySend(Entry(key, block)).isSuccess
        if (!sent && key != null) {
            synchronized(pending) {
                pending.remove(key)
            }
        }
        return sent
    }

    suspend fun <T> run(key: String? = null, block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        if (!launch(key) {
            runCatching { block() }
                .onSuccess(done::complete)
                .onFailure(done::completeExceptionally)
        }) {
            throw CancellationException("mutation pipeline is closed")
        }
        return done.await()
    }

    fun cancel() {
        queue.close()
        worker.cancel()
    }
}

private const val StreamRestartDelayMs = 3000L
private const val SyncDelayMs = 300L
private const val SyncBurstDelayMs = 1200L
private const val SyncFetchTimeoutMs = 15000L
private const val SyncBackoffBaseMs = 15000L
private const val SyncBackoffMaxMs = 30 * 60 * 1000L
private const val SyncSseQuietWindowMs = 20000L
private const val SyncToolStuckMs = 5 * 60 * 1000L
private const val OverlayFlushDelayMs = 500L
private const val MessageSyncLimit = 400
private const val ReconcileKeepPasses = 1
private const val OptimisticKeepPasses = 1
private const val LogTag = "SessionService"
private const val SessionResolveCooldownMs = 5000L
private const val InitialSessionLimit = 50
private const val SessionLimitStep = 50
private const val SessionFetchLimit = 50
private const val FavoritePreloadLimit = 50
private const val WorkspaceSessionDisplayLimit = 3
private const val SyncPinnedWindowMs = 2 * 60 * 60 * 1000L
private const val SyncPinnedFavoriteWindowMs = 30 * 60 * 1000L
private const val SessionTitleMaxLength = 80
private const val DefaultSessionTitle = "New session"
private const val LaneMutation = "mutation"
private const val LaneIo = "io"
private const val LaneCpu = "cpu"
