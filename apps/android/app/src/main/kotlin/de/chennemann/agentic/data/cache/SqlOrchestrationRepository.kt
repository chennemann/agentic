package de.chennemann.agentic.data.cache

import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.orchestration.ClientConfigReducer
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.domain.orchestration.ShellProjectionReducer
import de.chennemann.agentic.domain.orchestration.ThreadProjectionReducer
import de.chennemann.agentic.t3.contract.EnvironmentClientConfig
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.PortableJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class SqlOrchestrationRepository(
    private val database: AgenticDb,
    private val dispatcher: CoroutineDispatcher,
) : OrchestrationRepository {
    private val lock = Mutex()
    private val mutableClientConfig = MutableStateFlow(ProjectionState<EnvironmentClientConfig>())
    override val clientConfig: StateFlow<ProjectionState<EnvironmentClientConfig>> = mutableClientConfig.asStateFlow()
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

    override suspend fun loadCached(environmentId: String) = withContext(dispatcher) {
        lock.withLock {
            currentEnvironmentId = environmentId
            mutableClientConfig.value = ProjectionState()
            mutableShell.value = ProjectionState()
            mutableFocusedThread.value = ProjectionState()
            mutableFocusedThreadId.value = null
            mutableSelectedProjectId.value = preference(environmentId, SelectedProjectPreference)
            projection(environmentId, ClientConfigKind, GlobalCacheKey)?.let {
                runCatching { PortableJson.decodeFromString<EnvironmentClientConfig>(it.payload) }
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
                runCatching { PortableJson.decodeFromString<OrchestrationShellSnapshot>(it.payload) }
                    .getOrNull()
                    ?.let { shell ->
                        mutableShell.value = ShellProjectionReducer.snapshot(
                            mutableShell.value,
                            shell,
                            ProjectionSource.CACHE,
                        )
                    }
            }
            Unit
        }
    }

    override suspend fun setClientConfig(
        environmentId: String,
        config: EnvironmentClientConfig,
        source: ProjectionSource,
    ) = withContext(dispatcher) {
        lock.withLock {
            if (environmentId != currentEnvironmentId) return@withLock
            mutableClientConfig.value = ClientConfigReducer.reduce(mutableClientConfig.value, config, source)
            cache(
                environmentId,
                ClientConfigKind,
                GlobalCacheKey,
                null,
                PortableJson.encodeToString(config),
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
            cache(
                environmentId,
                ShellKind,
                GlobalCacheKey,
                snapshot.snapshotSequence,
                PortableJson.encodeToString(snapshot),
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
                if (
                    item is OrchestrationShellStreamItem.ThreadRemoved &&
                    mutableFocusedThreadId.value == item.threadId
                ) {
                    mutableFocusedThreadId.value = null
                    mutableFocusedThread.value = ProjectionState()
                    database.agenticT3Queries.deleteProjection(environmentId, ThreadKind, item.threadId)
                }
                reduction.state.value?.let {
                    cache(environmentId, ShellKind, GlobalCacheKey, reduction.state.sequence, PortableJson.encodeToString(it))
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
            mutableFocusedThreadId.value = threadId
            mutableFocusedThread.value = ProjectionState()
            if (threadId != null) {
                projection(environmentId, ThreadKind, threadId)?.let {
                    runCatching { PortableJson.decodeFromString<OrchestrationThreadDetailSnapshot>(it.payload) }
                        .getOrNull()
                        ?.let { snapshot ->
                            mutableFocusedThread.value = ThreadProjectionReducer.snapshot(
                                mutableFocusedThread.value,
                                snapshot,
                                ProjectionSource.CACHE,
                            )
                        }
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
                environmentId != currentEnvironmentId ||
                snapshot.thread.id != mutableFocusedThreadId.value
            ) {
                return@withLock
            }
            mutableFocusedThread.value = ThreadProjectionReducer.snapshot(
                mutableFocusedThread.value,
                snapshot,
                source,
            )
            cache(
                environmentId,
                ThreadKind,
                snapshot.thread.id,
                snapshot.snapshotSequence,
                PortableJson.encodeToString(snapshot),
            )
        }
    }

    override suspend fun applyThreadItem(
        environmentId: String,
        item: OrchestrationThreadStreamItem,
    ): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>> = withContext(dispatcher) {
        lock.withLock {
            if (environmentId != currentEnvironmentId) {
                return@withLock Reduction.Ignored(mutableFocusedThread.value)
            }
            val reduction = ThreadProjectionReducer.reduce(mutableFocusedThread.value, item)
            if (reduction is Reduction.Applied) {
                mutableFocusedThread.value = reduction.state
                val value = reduction.state.value
                if (value == null) {
                    mutableFocusedThreadId.value?.let {
                        database.agenticT3Queries.deleteProjection(environmentId, ThreadKind, it)
                    }
                    mutableFocusedThreadId.value = null
                } else {
                    cache(
                        environmentId,
                        ThreadKind,
                        value.thread.id,
                        reduction.state.sequence,
                        PortableJson.encodeToString(value),
                    )
                }
            }
            reduction
        }
    }

    override suspend fun clearEnvironment(environmentId: String) = withContext(dispatcher) {
        lock.withLock {
            database.agenticT3Queries.deleteEnvironmentProjections(environmentId)
            database.agenticT3Queries.deleteEnvironmentPreferences(environmentId)
            if (currentEnvironmentId == environmentId) {
                currentEnvironmentId = null
                mutableClientConfig.value = ProjectionState()
                mutableShell.value = ProjectionState()
                mutableFocusedThread.value = ProjectionState()
                mutableFocusedThreadId.value = null
                mutableSelectedProjectId.value = null
            }
        }
    }

    private fun projection(
        environmentId: String,
        kind: String,
        key: String,
    ): CachedProjection? = database.agenticT3Queries.selectProjection(
        environmentId,
        kind,
        key,
    ) { _, _, _, _, sequence, payload, _ ->
        CachedProjection(sequence, payload)
    }.executeAsOneOrNull()

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

    private data class CachedProjection(
        val sequence: Long?,
        val payload: String,
    )

    private companion object {
        const val CacheSchemaVersion = 1L
        const val ClientConfigKind = "client-config"
        const val ShellKind = "shell"
        const val ThreadKind = "thread"
        const val GlobalCacheKey = "current"
        const val SelectedProjectPreference = "selected-project"
    }
}
