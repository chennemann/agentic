package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

fun interface ThreadHistory {
    suspend fun loadOlder()
}

class ThreadHistoryService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val repository: OrchestrationRepository,
    private val rpc: T3RpcClient,
) : ThreadHistory {
    override suspend fun loadOlder() {
        val environment = environments.activeEnvironment.value ?: return
        val config = repository.clientConfig.value.value ?: return
        if (config.environment.environmentId != environment.id || !config.threadSnapshotPagination) return
        val current = repository.focusedThread.value
        val snapshot = current.value ?: return
        val page = snapshot.page?.takeIf { it.hasMore } ?: return
        val cursor = page.beforeCursor ?: return
        val token = requireNotNull(credentials.read(environment.id)) { "Pair this environment again." }
        val incoming = rpc.threadHistory(
            environment.baseUrl, token, snapshot.thread.id, cursor, PageTurnLimit, config.reasoningMessages,
        )
        val caughtUp = withTimeoutOrNull(HistoryCatchUpTimeoutMillis) {
            repository.focusedThread.first {
                environments.activeEnvironment.value?.id != environment.id ||
                    it.value?.thread?.id != snapshot.thread.id ||
                    it.historyGeneration != current.historyGeneration ||
                    (it.sequence ?: 0) >= (incoming.page?.threadSequence ?: 0)
            }
            true
        }
        check(caughtUp == true) { "Waiting for the conversation to reconnect. Try loading history again." }
        check(environments.activeEnvironment.value?.id == environment.id) { "The environment changed while loading history." }
        check(repository.mergeThreadHistory(environment.id, incoming, cursor, current.historyGeneration)) {
            "Conversation changed while loading history. Try again."
        }
    }

    companion object {
        const val PageTurnLimit = 20
        private const val HistoryCatchUpTimeoutMillis = 15_000L
    }
}
