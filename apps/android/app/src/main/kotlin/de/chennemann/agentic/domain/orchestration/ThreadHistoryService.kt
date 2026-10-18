package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository

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
        if (config.environment.environmentId != environment.id) return
        val current = repository.focusedThread.value
        val snapshot = current.value ?: return
        val page = snapshot.page?.takeIf { it.hasMore } ?: return
        val cursor = page.beforeCursor ?: return
        val token = requireNotNull(credentials.read(environment.id)) { "Pair this environment again." }
        val incoming = rpc.threadHistory(
            environment.baseUrl, token, snapshot.thread.id, cursor,
            snapshot.projection,
        )
        check(environments.activeEnvironment.value?.id == environment.id) { "The environment changed while loading history." }
        check(repository.mergeThreadHistory(environment.id, incoming, cursor, current.historyGeneration)) {
            "Conversation changed while loading history. Try again."
        }
    }
}
