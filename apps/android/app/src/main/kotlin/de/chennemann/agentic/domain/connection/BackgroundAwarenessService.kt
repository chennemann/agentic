package de.chennemann.agentic.domain.connection

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.ClientActivityReport
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

interface AppVisibility {
    fun setVisible(visible: Boolean)
}

fun interface ClientInstallationId {
    fun value(): String
}

class BackgroundAwarenessService(
    environments: EnvironmentRepository,
    orchestration: OrchestrationRepository,
    private val credentials: CredentialStore,
    private val rpc: T3RpcClient,
    private val installationId: ClientInstallationId,
    scope: CoroutineScope,
    private val reportIntervalMillis: Long = ReportIntervalMillis,
    private val now: () -> Instant = Instant::now,
) : AppVisibility {
    private val visible = MutableStateFlow(false)

    init {
        scope.launch {
            combine(
                environments.activeEnvironment,
                orchestration.clientConfig,
                orchestration.shell.map { shell ->
                    shell.value?.threads.orEmpty()
                        .filter { it.session?.status in TrackedThreadStatuses }
                        .mapTo(linkedSetOf()) { it.id }
                }.distinctUntilChanged(),
                visible,
            ) { environment, config, threadIds, isVisible ->
                val supported = config.value
                    ?.takeIf { it.environment.environmentId == environment?.id }
                    ?.environment
                    ?.capabilities
                    ?.backgroundActivity == true
                AwarenessDemand(environment?.id, environment?.baseUrl, supported, threadIds, isVisible)
            }.distinctUntilChanged().collectLatest { demand ->
                if (!demand.supported || demand.environmentId == null || demand.baseUrl == null) {
                    awaitCancellation()
                }
                val token = credentials.read(demand.environmentId) ?: awaitCancellation()
                do {
                    report(demand, token)
                    if (!demand.visible && demand.threadIds.isEmpty()) awaitCancellation()
                    delay(reportIntervalMillis)
                } while (true)
            }
        }
    }

    override fun setVisible(visible: Boolean) {
        this.visible.value = visible
    }

    private suspend fun report(demand: AwarenessDemand, token: String) {
        try {
            rpc.reportClientActivity(
                baseUrl = requireNotNull(demand.baseUrl),
                bearerToken = token,
                report = ClientActivityReport(
                    environmentId = requireNotNull(demand.environmentId),
                    clientId = "mobile-${installationId.value()}",
                    visible = demand.visible,
                    appState = if (demand.visible) "active" else "background",
                    threadIds = demand.threadIds,
                    observedAt = now().toString(),
                ),
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // A lease report is advisory. Projection catch-up remains authoritative.
        }
    }

    private data class AwarenessDemand(
        val environmentId: String?,
        val baseUrl: String?,
        val supported: Boolean,
        val threadIds: Set<String>,
        val visible: Boolean,
    )

    private companion object {
        const val ReportIntervalMillis = 25_000L
        val TrackedThreadStatuses = setOf("starting", "running", "waiting_for_approval", "waiting_for_input")
    }
}
