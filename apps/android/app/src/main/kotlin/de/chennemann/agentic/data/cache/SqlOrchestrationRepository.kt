package de.chennemann.agentic.data.cache

import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.orchestration.ClientConfigReducer
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.domain.orchestration.ShellProjectionReducer
import de.chennemann.agentic.domain.orchestration.ThreadProjectionReducer
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.T3Json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class SqlOrchestrationRepository(
    private val database: AgenticDb,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : OrchestrationRepository {
    private val lock = Mutex()
    private val cacheJobs = mutableMapOf<CacheIdentity, Job>()
    private val cacheGenerations = mutableMapOf<CacheIdentity, Long>()
    private val mutableClientConfig = MutableStateFlow(ProjectionState<ServerConfig>())
    override val clientConfig: StateFlow<ProjectionState<ServerConfig>> = mutableClientConfig.asStateFlow()
    private val mutableShell = MutableStateFlow(ProjectionState<OrchestrationShellSnapshot>())
    override val shell: StateFlow<ProjectionState<OrchestrationShellSnapshot>> = mutableShell.asStateFlow()
    private val mutableFocusedThread = MutableStateFlow(ProjectionState<OrchestrationThreadDetailSnapshot>())
    override val focusedThread: StateFlow<ProjectionState<OrchestrationThreadDetailSnapshot>> =
        mutableFocusedThread.asStateFlow()
    private val mutableFocusedThreadId = MutableStateFlow<String?>(null)
    override val focusedThreadId: StateFlow<String?> = mutableFocusedThreadId.asStateFlow()
    private val mutableSelectedProjectId = MutableStateFlow<String?>(null)
    override val selectedProjectId: StateFlow<String?> = mutableSelectedProjectId.asStateFlow()
    private var currentEnvironmentId: String? = null
    private val threadProjections = mutableMapOf<String, ProjectionState<OrchestrationThreadDetailSnapshot>>()

    override suspend fun loadCached(environmentId: String) = withContext(dispatcher) {
        lock.withLock {
            if (currentEnvironmentId == environmentId) return@withLock
            cancelScheduledCaches(environmentId)
            currentEnvironmentId = environmentId
            mutableClientConfig.value = ProjectionState()
            mutableShell.value = ProjectionState()
            mutableFocusedThread.value = ProjectionState()
            mutableFocusedThreadId.value = null
            threadProjections.clear()
            mutableSelectedProjectId.value = preference(environmentId, SelectedProjectPreference)
            val storedThreadId = preference(environmentId, LastThreadPreference)
            projection(environmentId, ClientConfigKind, GlobalCacheKey)?.let {
                runCatching { T3Json.decodeFromString<ServerConfig>(it.payload) }
                    .getOrNull()
                    ?.let { config ->
                        mutableClientConfig.value = ClientConfigReducer.reduce(
                            mutableClientConfig.value,
                            config,
                            ProjectionSource.CACHE,
                        )
                    }
            }
            projection(environmentId, ShellKind, GlobalCacheKey)?.let {
                runCatching { T3Json.decodeFromString<OrchestrationShellSnapshot>(it.payload) }
                    .getOrNull()
                    ?.let { shell ->
                        mutableShell.value = ShellProjectionReducer.snapshot(
                            mutableShell.value,
                            shell,
                            ProjectionSource.CACHE,
                        )
                    }
            }
            if (storedThreadId != null) {
                val restored = loadThreadProjection(environmentId, storedThreadId)?.also {
                    threadProjections[storedThreadId] = it
                }
                mutableFocusedThread.value = restored ?: ProjectionState()
                mutableFocusedThreadId.value = storedThreadId
                mutableShell.value.value
                    ?.threads
                    ?.firstOrNull { it.id == storedThreadId }
                    ?.let { mutableSelectedProjectId.value = it.projectId }
            }
            Unit
        }
    }

    override suspend fun setClientConfig(
        environmentId: String,
        config: ServerConfig,
        source: ProjectionSource,
    ) = withContext(dispatcher) {
        lock.withLock {
            if (environmentId != currentEnvironmentId) return@withLock
            mutableClientConfig.value = ClientConfigReducer.reduce(mutableClientConfig.value, config, source)
            cancelScheduledCache(environmentId, ClientConfigKind, GlobalCacheKey)
            cache(
                environmentId,
                ClientConfigKind,
                GlobalCacheKey,
                null,
                T3Json.encodeToString(config),
            )
        }
    }

    override suspend fun setShellSnapshot(
        environmentId: String,
        snapshot: OrchestrationShellSnapshot,
        source: ProjectionSource,
    ) = withContext(dispatcher) {
        lock.withLock {
            if (environmentId != currentEnvironmentId) return@withLock
            mutableShell.value = ShellProjectionReducer.snapshot(mutableShell.value, snapshot, source)
            if (source == ProjectionSource.LIVE) reconcileFocusedThread(environmentId)
            cancelScheduledCache(environmentId, ShellKind, GlobalCacheKey)
            cache(
                environmentId,
                ShellKind,
                GlobalCacheKey,
                snapshot.snapshotSequence,
                T3Json.encodeToString(snapshot),
            )
        }
    }

    override suspend fun applyShellItem(
        environmentId: String,
        item: OrchestrationShellStreamItem,
    ): Reduction<ProjectionState<OrchestrationShellSnapshot>> = withContext(dispatcher) {
        lock.withLock {
            if (environmentId != currentEnvironmentId) return@withLock Reduction.Ignored(mutableShell.value)
            val reduction = ShellProjectionReducer.reduce(mutableShell.value, item)
            if (reduction is Reduction.Applied) {
                mutableShell.value = reduction.state
                reconcileFocusedThread(environmentId)
                reduction.state.value?.let {
                    scheduleCache(
                        environmentId,
                        ShellKind,
                        GlobalCacheKey,
                        reduction.state.sequence,
                    ) {
                        T3Json.encodeToString(it)
                    }
                }
            }
            reduction
        }
    }

    override suspend fun focusThread(
        environmentId: String,
        threadId: String?,
    ) = withContext(dispatcher) {
        lock.withLock {
            if (environmentId != currentEnvironmentId) return@withLock
            val projection = threadId?.let { id ->
                threadProjections[id]
                    ?: loadThreadProjection(environmentId, id)?.also { threadProjections[id] = it }
            } ?: ProjectionState()
            mutableFocusedThread.value = projection
            mutableFocusedThreadId.value = threadId
            if (threadId != null) {
                database.agenticT3Queries.upsertPreference(
                    environmentId,
                    LastThreadPreference,
                    threadId,
                )
                mutableShell.value.value
                    ?.threads
                    ?.firstOrNull { it.id == threadId }
                    ?.let { thread ->
                        mutableSelectedProjectId.value = thread.projectId
                        database.agenticT3Queries.upsertPreference(
                            environmentId,
                            SelectedProjectPreference,
                            thread.projectId,
                        )
                    }
            }
        }
    }

    override suspend fun selectProject(
        environmentId: String,
        projectId: String?,
    ) = withContext(dispatcher) {
        lock.withLock {
            if (environmentId != currentEnvironmentId) return@withLock
            mutableSelectedProjectId.value = projectId
            if (projectId != null) {
                database.agenticT3Queries.upsertPreference(
                    environmentId,
                    SelectedProjectPreference,
                    projectId,
                )
            }
        }
    }

    override suspend fun setThreadSnapshot(
        environmentId: String,
        snapshot: OrchestrationThreadDetailSnapshot,
        source: ProjectionSource,
    ) = withContext(dispatcher) {
        lock.withLock {
            if (
                environmentId != currentEnvironmentId
            ) {
                return@withLock
            }
            val threadId = snapshot.thread.id
            val state = ThreadProjectionReducer.snapshot(
                threadProjections[threadId] ?: ProjectionState(),
                snapshot,
                source,
            )
            threadProjections[threadId] = state
            if (threadId == mutableFocusedThreadId.value) mutableFocusedThread.value = state
            cancelScheduledCache(environmentId, ThreadKind, snapshot.thread.id)
            cache(
                environmentId,
                ThreadKind,
                snapshot.thread.id,
                snapshot.snapshotSequence,
                T3Json.encodeToString(snapshot),
            )
        }
    }

    override suspend fun applyThreadItem(
        environmentId: String,
        threadId: String,
        item: OrchestrationThreadStreamItem,
    ): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>> = withContext(dispatcher) {
        lock.withLock {
            if (environmentId != currentEnvironmentId) {
                return@withLock Reduction.Ignored(threadProjections[threadId] ?: ProjectionState())
            }
            val reduction = ThreadProjectionReducer.reduce(
                threadProjections[threadId] ?: ProjectionState(),
                item,
            )
            if (reduction is Reduction.Applied) {
                threadProjections[threadId] = reduction.state
                if (threadId == mutableFocusedThreadId.value) mutableFocusedThread.value = reduction.state
                val value = reduction.state.value
                if (value == null) {
                    threadProjections.remove(threadId)
                    cancelScheduledCache(environmentId, ThreadKind, threadId)
                    database.agenticT3Queries.deleteProjection(environmentId, ThreadKind, threadId)
                    if (threadId == mutableFocusedThreadId.value) {
                        mutableFocusedThreadId.value = null
                        mutableFocusedThread.value = ProjectionState()
                        database.agenticT3Queries.deletePreference(environmentId, LastThreadPreference)
                    }
                } else {
                    scheduleCache(
                        environmentId,
                        ThreadKind,
                        value.thread.id,
                        reduction.state.sequence,
                    ) {
                        T3Json.encodeToString(value)
                    }
                }
            }
            reduction
        }
    }

    override suspend fun clearEnvironment(environmentId: String) = withContext(dispatcher) {
        lock.withLock {
            cancelScheduledCaches(environmentId)
            database.agenticT3Queries.deleteEnvironmentProjections(environmentId)
            database.agenticT3Queries.deleteEnvironmentPreferences(environmentId)
            if (currentEnvironmentId == environmentId) {
                currentEnvironmentId = null
                mutableClientConfig.value = ProjectionState()
                mutableShell.value = ProjectionState()
                mutableFocusedThread.value = ProjectionState()
                mutableFocusedThreadId.value = null
                mutableSelectedProjectId.value = null
                threadProjections.clear()
            }
        }
    }

    override suspend fun clearProjectionCache(environmentId: String) = withContext(dispatcher) {
        lock.withLock {
            cancelScheduledCaches(environmentId)
            database.transaction { database.agenticT3Queries.deleteEnvironmentProjections(environmentId) }
            if (currentEnvironmentId == environmentId) {
                mutableClientConfig.value = ProjectionState()
                mutableShell.value = ProjectionState()
                mutableFocusedThread.value = ProjectionState()
                threadProjections.clear()
            }
        }
    }

    private fun loadThreadProjection(
        environmentId: String,
        threadId: String,
    ): ProjectionState<OrchestrationThreadDetailSnapshot>? =
        projection(environmentId, ThreadKind, threadId)?.let {
            runCatching { T3Json.decodeFromString<OrchestrationThreadDetailSnapshot>(it.payload) }
                .getOrNull()
                ?.let { snapshot ->
                    ThreadProjectionReducer.snapshot(
                        ProjectionState(),
                        snapshot,
                        ProjectionSource.CACHE,
                    )
                }
        }

    private fun reconcileFocusedThread(environmentId: String) {
        val threadId = mutableFocusedThreadId.value ?: return
        val shell = mutableShell.value.value ?: return
        val thread = shell.threads.firstOrNull { it.id == threadId }
        if (thread == null) {
            cancelScheduledCache(environmentId, ThreadKind, threadId)
            threadProjections.remove(threadId)
            mutableFocusedThreadId.value = null
            mutableFocusedThread.value = ProjectionState()
            database.agenticT3Queries.deleteProjection(environmentId, ThreadKind, threadId)
            database.agenticT3Queries.deletePreference(environmentId, LastThreadPreference)
        } else if (mutableSelectedProjectId.value != thread.projectId) {
            mutableSelectedProjectId.value = thread.projectId
            database.agenticT3Queries.upsertPreference(
                environmentId,
                SelectedProjectPreference,
                thread.projectId,
            )
        }
    }

    private fun projection(
        environmentId: String,
        kind: String,
        key: String,
    ): CachedProjection? {
        val payloadByteCount = database.agenticT3Queries.selectProjectionPayloadByteCount(
            environmentId,
            kind,
            key,
        ).executeAsOneOrNull() ?: return null
        if (payloadByteCount > MaxCachedProjectionPayloadBytes) {
            database.agenticT3Queries.deleteProjection(environmentId, kind, key)
            return null
        }
        return database.agenticT3Queries.selectProjection(
            environmentId,
            kind,
            key,
        ) { sequence, payload ->
            CachedProjection(sequence, payload)
        }.executeAsOneOrNull()
    }

    private fun preference(
        environmentId: String,
        key: String,
    ): String? = database.agenticT3Queries.selectPreference(environmentId, key).executeAsOneOrNull()

    private fun cache(
        environmentId: String,
        kind: String,
        key: String,
        sequence: Long?,
        payload: String,
    ) {
        if (payload.encodeToByteArray().size > MaxCachedProjectionPayloadBytes) {
            database.agenticT3Queries.deleteProjection(environmentId, kind, key)
            return
        }
        database.agenticT3Queries.upsertProjection(
            environmentId,
            kind,
            key,
            CacheSchemaVersion,
            sequence,
            payload,
            System.currentTimeMillis(),
        )
    }

    private fun scheduleCache(
        environmentId: String,
        kind: String,
        key: String,
        sequence: Long?,
        payload: () -> String,
    ) {
        val identity = CacheIdentity(environmentId, kind, key)
        val generation = cacheGenerations.getOrDefault(identity, 0) + 1
        cacheGenerations[identity] = generation
        cacheJobs.remove(identity)?.cancel()
        cacheJobs[identity] = scope.launch(dispatcher) {
            delay(CacheWriteDebounceMillis)
            val encoded = payload()
            lock.withLock {
                if (
                    currentEnvironmentId == environmentId &&
                    cacheGenerations[identity] == generation
                ) {
                    cache(environmentId, kind, key, sequence, encoded)
                }
            }
        }
    }

    private fun cancelScheduledCache(
        environmentId: String,
        kind: String,
        key: String,
    ) {
        val identity = CacheIdentity(environmentId, kind, key)
        cacheGenerations[identity] = cacheGenerations.getOrDefault(identity, 0) + 1
        cacheJobs.remove(identity)?.cancel()
    }

    private fun cancelScheduledCaches(environmentId: String) {
        cacheJobs.keys
            .filter { it.environmentId == environmentId }
            .forEach { identity ->
                cacheGenerations[identity] = cacheGenerations.getOrDefault(identity, 0) + 1
                cacheJobs.remove(identity)?.cancel()
            }
    }

    private data class CachedProjection(
        val sequence: Long?,
        val payload: String,
    )

    private data class CacheIdentity(
        val environmentId: String,
        val kind: String,
        val key: String,
    )

    private companion object {
        const val CacheSchemaVersion = 1L
        const val CacheWriteDebounceMillis = 500L
        // Leave headroom below Android's commonly 2 MiB CursorWindow limit.
        const val MaxCachedProjectionPayloadBytes = 1_500_000
        const val ClientConfigKind = "client-config"
        const val ShellKind = "shell"
        const val ThreadKind = "thread"
        const val GlobalCacheKey = "current"
        const val SelectedProjectPreference = "selected-project"
        const val LastThreadPreference = "last-thread"
    }
}
