package de.chennemann.agentic.domain.environment

import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository

data class CacheCategoryUsage(val category: String, val entries: Long, val bytes: Long, val protected: Boolean)
data class EnvironmentCacheUsage(val environmentId: String, val categories: List<CacheCategoryUsage>) {
    val clearableBytes get() = categories.filterNot { it.protected }.sumOf { it.bytes }
}

interface EnvironmentCacheInspector {
    suspend fun usage(environmentId: String): EnvironmentCacheUsage
    suspend fun clear(environmentId: String)
}

interface EnvironmentCacheActions {
    suspend fun usage(environmentId: String): EnvironmentCacheUsage
    suspend fun clear(environmentId: String)
}

class EnvironmentCacheService(
    private val environments: EnvironmentRepository,
    private val inspector: EnvironmentCacheInspector,
    private val projections: OrchestrationRepository,
    private val connection: ConnectionController,
) : EnvironmentCacheActions {
    override suspend fun usage(environmentId: String): EnvironmentCacheUsage {
        require(environments.environments.value.any { it.id == environmentId }) { "Environment is no longer registered." }
        return inspector.usage(environmentId)
    }

    override suspend fun clear(environmentId: String) {
        require(environments.environments.value.any { it.id == environmentId }) { "Environment is no longer registered." }
        val protected = inspector.usage(environmentId).categories.filter { it.protected && it.entries > 0 }
        require(protected.isEmpty()) {
            "Cache clearing is blocked by ${protected.joinToString { it.category.lowercase() }}."
        }
        inspector.clear(environmentId)
        projections.clearProjectionCache(environmentId)
        if (environments.activeEnvironment.value?.id == environmentId) connection.wake()
    }
}
