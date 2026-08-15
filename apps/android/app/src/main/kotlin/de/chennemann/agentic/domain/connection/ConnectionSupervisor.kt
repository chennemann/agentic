package de.chennemann.agentic.domain.connection

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.EnvironmentMetadataClient
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.data.t3.T3TransportException
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.PendingCommandReplayer
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
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
    private val rpc: T3RpcClient,
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
            mutableState.value = ConnectionState.Connecting
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
        if (descriptor.environmentId != environment.id) throw EnvironmentMismatch()
        val clientConfig = rpc.serverConfig(environment.baseUrl, token)
        if (clientConfig.environment.environmentId != environment.id) throw EnvironmentMismatch()
        orchestration.setClientConfig(environment.id, clientConfig, ProjectionSource.LIVE)
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
        var forceSnapshot = orchestration.shell.value.sequence == null
        while (true) {
            val sequence = if (forceSnapshot) null else orchestration.shell.value.sequence
            var gap = false
            try {
                rpc.shellStream(
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
                            markSynchronized(environment)
                            retryDelay = InitialRetryMillis
                            consecutiveFailures = 0
                            forceSnapshot = false
                        }

                        is Reduction.Ignored -> Unit
                    }
                    if (gap) throw SequenceGap()
                }
            } catch (_: SequenceGap) {
                forceSnapshot = true
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
        var retryDelay = InitialRetryMillis
        var sequence = orchestration.focusedThread.value
            .takeIf { it.value?.thread?.id == threadId }
            ?.sequence
        while (true) {
            try {
                var gap = false
                rpc.threadStream(
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
                                markSynchronized(environment)
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
                sequence = null
                retryDelay = InitialRetryMillis
                continue
            } catch (_: Exception) {
                if (!network.online.value) return
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MaxRetryMillis)
            }
        }
    }

    private suspend fun markSynchronized(environment: SavedEnvironment) {
        mutableState.value = ConnectionState.Live
        environments.markConnected(environment.id, System.currentTimeMillis())
        replayPendingCommands(environment)
    }

    private class SequenceGap : Exception("Projection sequence gap detected.")

    private class EnvironmentMismatch : Exception("The T3 environment identity changed.")

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
