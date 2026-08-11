package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import kotlinx.coroutines.flow.StateFlow

enum class ProjectionSource {
    CACHE,
    LIVE,
}

data class ProjectionState<T>(
    val value: T? = null,
    val sequence: Long? = null,
    val source: ProjectionSource? = null,
    val synchronized: Boolean = false,
)

sealed interface Reduction<out T> {
    data class Applied<T>(
        val state: T,
    ) : Reduction<T>

    data class Ignored<T>(
        val state: T,
    ) : Reduction<T>

    data class Gap<T>(
        val state: T,
        val expected: Long,
        val received: Long,
    ) : Reduction<T>
}

interface OrchestrationRepository {
    val clientConfig: StateFlow<ProjectionState<ServerConfig>>
    val shell: StateFlow<ProjectionState<OrchestrationShellSnapshot>>
    val focusedThread: StateFlow<ProjectionState<OrchestrationThreadDetailSnapshot>>
    val focusedThreadId: StateFlow<String?>
    val selectedProjectId: StateFlow<String?>

    suspend fun loadCached(environmentId: String)

    suspend fun setClientConfig(
        environmentId: String,
        config: ServerConfig,
        source: ProjectionSource,
    )

    suspend fun setShellSnapshot(
        environmentId: String,
        snapshot: OrchestrationShellSnapshot,
        source: ProjectionSource,
    )

    suspend fun applyShellItem(
        environmentId: String,
        item: de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem,
    ): Reduction<ProjectionState<OrchestrationShellSnapshot>>

    suspend fun focusThread(
        environmentId: String,
        threadId: String?,
    )

    suspend fun selectProject(
        environmentId: String,
        projectId: String?,
    )

    suspend fun setThreadSnapshot(
        environmentId: String,
        snapshot: OrchestrationThreadDetailSnapshot,
        source: ProjectionSource,
    )

    suspend fun applyThreadItem(
        environmentId: String,
        threadId: String,
        item: de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem,
    ): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>>

    suspend fun clearEnvironment(environmentId: String)

    suspend fun clearProjectionCache(environmentId: String): Unit = error("Projection cache clearing is not supported.")
}
