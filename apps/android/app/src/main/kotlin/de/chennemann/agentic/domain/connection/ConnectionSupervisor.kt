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
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.T3_PORTABLE_PROTOCOL_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ConnectionSupervisor(
    private val environments: EnvironmentRepository,
    private val orchestration: OrchestrationRepository,
    private val credentials: CredentialStore,
    private val metadata: EnvironmentMetadataClient,
    private val config: EnvironmentConfigClient,
    private val snapshots: OrchestrationSnapshotClient,
    private val streams: OrchestrationStreamClient,
    private val network: NetworkMonitor,
    scope: CoroutineScope,
) : ConnectionController {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.NoEnvironment)
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    private val wakeups = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        scope.launch {
            environments.activeEnvironment
                .map { it?.copy(lastConnectedAt = null) }
                .distinctUntilChanged()
                .collectLatest { environment ->
                    if (environment == null) {
                        mutableState.value = ConnectionState.NoEnvironment
                    } else {
                        supervise(environment)
                    }
                }
        }
    }

    override fun wake() {
        wakeups.tryEmit(Unit)
    }

    private suspend fun supervise(environment: SavedEnvironment) {
        orchestration.loadCached(environment.id)
        if (orchestration.shell.value.value != null) mutableState.value = ConnectionState.Cached
        var retryDelay = InitialRetryMillis
        while (true) {
            network.online.first { it }
            try {
                connect(environment)
                retryDelay = InitialRetryMillis
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: T3TransportException.Authentication) {
                mutableState.value = ConnectionState.BlockedAuthentication(
                    "Authentication expired. Pair this environment again.",
                )
                wakeups.first()
            } catch (_: UnsupportedProtocol) {
                mutableState.value = ConnectionState.UnsupportedProtocol(
                    "This environment does not support T3 portable client protocol v1.",
                )
                wakeups.first()
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
            orchestration.focusedThreadId
                .collectLatest { threadId ->
                    if (threadId != null) superviseThread(environment, token, threadId)
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
    }

    private suspend fun superviseShell(
        environment: SavedEnvironment,
        token: String,
        requestMarker: Boolean,
    ) {
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
                        }

                        is Reduction.Ignored -> Unit
                    }
                    if (gap) throw SequenceGap()
                }
            } catch (_: SequenceGap) {
                refreshShell(environment, token)
                continue
            }
            throw T3TransportException.Network()
        }
    }

    private suspend fun superviseThread(
        environment: SavedEnvironment,
        token: String,
        threadId: String,
    ) {
        while (orchestration.focusedThreadId.value == threadId) {
            val snapshot = snapshots.threadSnapshot(environment.baseUrl, token, threadId)
            orchestration.setThreadSnapshot(environment.id, snapshot, ProjectionSource.LIVE)
            var gap = false
            try {
                streams.threadStream(
                    environment.baseUrl,
                    token,
                    threadId,
                    snapshot.snapshotSequence,
                    requestCompletionMarker = true,
                ).collect { item ->
                    when (orchestration.applyThreadItem(environment.id, item)) {
                        is Reduction.Gap -> gap = true
                        is Reduction.Applied -> if (item is OrchestrationThreadStreamItem.Synchronized) {
                            mutableState.value = ConnectionState.Live
                        }

                        is Reduction.Ignored -> Unit
                    }
                    if (gap) throw SequenceGap()
                }
            } catch (_: SequenceGap) {
                continue
            }
            throw T3TransportException.Network()
        }
    }

    private class SequenceGap : Exception("Projection sequence gap detected.")

    private class UnsupportedProtocol : Exception()

    private companion object {
        const val InitialRetryMillis = 1_000L
        const val MaxRetryMillis = 16_000L
    }
}
