package de.chennemann.agentic.domain.connection

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.EnvironmentConfigClient
import de.chennemann.agentic.data.t3.EnvironmentMetadataClient
import de.chennemann.agentic.data.t3.OrchestrationSnapshotClient
import de.chennemann.agentic.data.t3.OrchestrationStreamClient
import de.chennemann.agentic.data.t3.T3TransportException
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.PendingCommandReplayer
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.T3_PORTABLE_PROTOCOL_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionSupervisor(
    private val environments: EnvironmentRepository,
    private val orchestration: OrchestrationRepository,
    private val credentials: CredentialStore,
    private val metadata: EnvironmentMetadataClient,
    private val config: EnvironmentConfigClient,
    private val snapshots: OrchestrationSnapshotClient,
    private val streams: OrchestrationStreamClient,
    private val network: NetworkMonitor,
    private val pendingCommands: PendingCommandReplayer,
    private val scope: CoroutineScope,
) : ConnectionController {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.NoEnvironment)
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    private val restartGeneration = MutableStateFlow(0L)
    private val replayingPendingCommands = AtomicBoolean(false)

    init {
        scope.launch {
            combine(
                environments.activeEnvironment
                    .map { it?.copy(lastConnectedAt = null) }
                    .distinctUntilChanged(),
                network.online,
                restartGeneration,
            ) { environment, online, generation ->
                ConnectionTarget(environment, online, generation)
            }.collectLatest { target ->
                val environment = target.environment
                if (environment == null) {
                    mutableState.value = ConnectionState.NoEnvironment
                } else if (!target.online) {
                    orchestration.loadCached(environment.id)
                    mutableState.value = if (orchestration.shell.value.value == null) {
                        ConnectionState.Backoff(
                            retryInMillis = 0,
                            message = "Waiting for a network connection.",
                        )
                    } else {
                        ConnectionState.Cached
                    }
                    awaitCancellation()
                } else {
                    supervise(environment)
                }
            }
        }
    }

    override fun wake() {
        if (mutableState.value != ConnectionState.Live) {
            restartGeneration.update { it + 1 }
        }
    }

    override fun retryPendingCommands() {
        val environment = environments.activeEnvironment.value ?: return
        if (!replayingPendingCommands.compareAndSet(false, true)) return
        scope.launch {
            try {
                pendingCommands.replay(environment)
            } finally {
                replayingPendingCommands.set(false)
            }
        }
    }

    private suspend fun supervise(environment: SavedEnvironment) {
        orchestration.loadCached(environment.id)
        if (orchestration.shell.value.value != null) mutableState.value = ConnectionState.Cached
        var retryDelay = InitialRetryMillis
        while (true) {
            try {
                connect(environment)
                retryDelay = InitialRetryMillis
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: T3TransportException.Authentication) {
                mutableState.value = ConnectionState.BlockedAuthentication(
                    "Authentication expired. Pair this environment again.",
                )
                awaitCancellation()
            } catch (_: UnsupportedProtocol) {
                mutableState.value = ConnectionState.UnsupportedProtocol(
                    "This environment does not support T3 portable client protocol v1.",
                )
                awaitCancellation()
            } catch (cause: Exception) {
                if (!network.online.value) continue
                mutableState.value = ConnectionState.Backoff(
                    retryInMillis = retryDelay,
                    message = cause.message ?: "Connection interrupted.",
                )
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MaxRetryMillis)
            }
        }
    }

    private suspend fun connect(environment: SavedEnvironment) = coroutineScope {
        mutableState.value = ConnectionState.Connecting
        val token = credentials.read(environment.id)
            ?: throw T3TransportException.Authentication(401)
        val descriptor = metadata.environmentDescriptor(environment.baseUrl)
        if (
            descriptor.environmentId != environment.id ||
            descriptor.capabilities.portableClientProtocol != T3_PORTABLE_PROTOCOL_VERSION
        ) {
            throw UnsupportedProtocol()
        }
        val clientConfig = config.clientConfig(environment.baseUrl, token)
        if (
            clientConfig.protocolVersion != T3_PORTABLE_PROTOCOL_VERSION ||
            clientConfig.environment.environmentId != environment.id
        ) {
            throw UnsupportedProtocol()
        }
        orchestration.setClientConfig(environment.id, clientConfig, ProjectionSource.LIVE)
        refreshShell(environment, token)
        mutableState.value = ConnectionState.Synchronizing
        launch {
            combine(orchestration.shell, orchestration.focusedThreadId) { shell, focusedThreadId ->
                shell.value?.threads.orEmpty()
                    .filter { it.session?.status in ActiveThreadStatuses }
                    .mapTo(mutableSetOf()) { it.id }
                    .apply { focusedThreadId?.let(::add) }
            }.distinctUntilChanged().collectLatest { threadIds ->
                coroutineScope {
                    threadIds.forEach { threadId ->
                        launch { superviseThread(environment, token, threadId) }
                    }
                }
            }
        }
        superviseShell(environment, token, clientConfig.shellResumeCompletionMarker)
    }

    private suspend fun refreshShell(
        environment: SavedEnvironment,
        token: String,
    ) {
        val shell = snapshots.shellSnapshot(environment.baseUrl, token)
        orchestration.setShellSnapshot(environment.id, shell, ProjectionSource.LIVE)
        environments.markConnected(environment.id, System.currentTimeMillis())
        replayPendingCommands(environment)
    }

    private suspend fun replayPendingCommands(environment: SavedEnvironment) {
        if (!replayingPendingCommands.compareAndSet(false, true)) return
        try {
            pendingCommands.replay(environment)
        } finally {
            replayingPendingCommands.set(false)
        }
    }

    private suspend fun superviseShell(
        environment: SavedEnvironment,
        token: String,
        requestMarker: Boolean,
    ) {
        var retryDelay = InitialRetryMillis
        var consecutiveFailures = 0
        while (true) {
            val sequence = orchestration.shell.value.sequence ?: 0
            var gap = false
            try {
                streams.shellStream(
                    environment.baseUrl,
                    token,
                    sequence,
                    requestMarker,
                ).collect { item ->
                    when (orchestration.applyShellItem(environment.id, item)) {
                        is Reduction.Gap -> gap = true
                        is Reduction.Applied -> if (
                            item is OrchestrationShellStreamItem.Synchronized ||
                            !requestMarker
                        ) {
                            mutableState.value = ConnectionState.Live
                            retryDelay = InitialRetryMillis
                            consecutiveFailures = 0
                        }

                        is Reduction.Ignored -> Unit
                    }
                    if (gap) throw SequenceGap()
                }
            } catch (_: SequenceGap) {
                refreshShell(environment, token)
                retryDelay = InitialRetryMillis
                consecutiveFailures = 0
                continue
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: T3TransportException.Authentication) {
                throw cause
            } catch (cause: Exception) {
                if (!network.online.value) awaitCancellation()
                consecutiveFailures++
                if (consecutiveFailures >= FailuresBeforeBackoffUi) {
                    mutableState.value = ConnectionState.Backoff(
                        retryInMillis = retryDelay,
                        message = cause.message ?: "Connection interrupted.",
                    )
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MaxRetryMillis)
                continue
            }
            delay(StreamReconnectDelayMillis)
        }
    }

    private suspend fun superviseThread(
        environment: SavedEnvironment,
        token: String,
        threadId: String,
    ) {
        var needsSnapshot = true
        var retryDelay = InitialRetryMillis
        var sequence = 0L
        while (true) {
            try {
                if (needsSnapshot) {
                    val snapshot = snapshots.threadSnapshot(environment.baseUrl, token, threadId)
                    orchestration.setThreadSnapshot(environment.id, snapshot, ProjectionSource.LIVE)
                    sequence = snapshot.snapshotSequence
                    needsSnapshot = false
                }
                var gap = false
                streams.threadStream(
                    environment.baseUrl,
                    token,
                    threadId,
                    sequence,
                    requestCompletionMarker = true,
                ).collect { item ->
                    when (val reduction = orchestration.applyThreadItem(environment.id, threadId, item)) {
                        is Reduction.Gap -> gap = true
                        is Reduction.Applied -> {
                            sequence = reduction.state.sequence ?: sequence
                            if (item is OrchestrationThreadStreamItem.Synchronized) {
                                mutableState.value = ConnectionState.Live
                            }
                        }

                        is Reduction.Ignored -> sequence = reduction.state.sequence ?: sequence
                    }
                    if (gap) throw SequenceGap()
                }
                retryDelay = InitialRetryMillis
                delay(StreamReconnectDelayMillis)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: T3TransportException.Authentication) {
                throw cause
            } catch (_: SequenceGap) {
                needsSnapshot = true
                retryDelay = InitialRetryMillis
                continue
            } catch (_: Exception) {
                if (!network.online.value) return
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MaxRetryMillis)
            }
        }
    }

    private class SequenceGap : Exception("Projection sequence gap detected.")

    private class UnsupportedProtocol : Exception()

    private data class ConnectionTarget(
        val environment: SavedEnvironment?,
        val online: Boolean,
        @Suppress("unused") val generation: Long,
    )

    private companion object {
        const val InitialRetryMillis = 1_000L
        const val MaxRetryMillis = 16_000L
        const val StreamReconnectDelayMillis = 250L
        const val FailuresBeforeBackoffUi = 3
        val ActiveThreadStatuses = setOf("starting", "running")
    }
}
