package de.chennemann.agentic.domain.environment

import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.connection.ConnectionState
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.t3.contract.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnvironmentCacheServiceTest {
    @Test fun `draft and outbox block clear without changing registration`() = runTest {
        val environments = CacheEnvironmentRepository()
        val inspector = CacheInspector(listOf(CacheCategoryUsage("Unsent drafts", 1, 5, true)))
        val projections = CacheProjectionRepository()
        val service = EnvironmentCacheService(environments, inspector, projections, CacheConnection())
        val failure = runCatching { service.clear("target") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("unsent drafts") == true)
        assertFalse(inspector.cleared)
        assertTrue(projections.cleared.isEmpty())
        assertEquals(listOf("target", "other"), environments.environments.value.map { it.id })
    }
}

private class CacheInspector(private val categories: List<CacheCategoryUsage>) : EnvironmentCacheInspector {
    var cleared = false
    override suspend fun usage(environmentId: String) = EnvironmentCacheUsage(environmentId, categories)
    override suspend fun clear(environmentId: String) { cleared = true }
}
private class CacheConnection : ConnectionController {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Live)
    override fun wake() = Unit
    override fun retryPendingCommands() = Unit
}
private class CacheEnvironmentRepository : EnvironmentRepository {
    override val environments = MutableStateFlow(listOf(cacheEnvironment("target", true), cacheEnvironment("other", false)))
    override val activeEnvironment = MutableStateFlow<SavedEnvironment?>(environments.value.first())
    override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) = Unit
    override suspend fun select(environmentId: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
    override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
}
private fun cacheEnvironment(id: String, active: Boolean) = SavedEnvironment(id, id, "https://example.test/", "linux", "x64", "1", active, null)
private class CacheProjectionRepository : OrchestrationRepository {
    val cleared = mutableListOf<String>()
    override val clientConfig = MutableStateFlow(ProjectionState<ServerConfig>())
    override val shell = MutableStateFlow(ProjectionState<OrchestrationShellSnapshot>())
    override val focusedThread = MutableStateFlow(ProjectionState<OrchestrationThreadDetailSnapshot>())
    override val focusedThreadId = MutableStateFlow<String?>(null); override val selectedProjectId = MutableStateFlow<String?>(null)
    override suspend fun loadCached(environmentId: String)=Unit
    override suspend fun setClientConfig(environmentId:String,config:ServerConfig,source:ProjectionSource)=Unit
    override suspend fun setShellSnapshot(environmentId:String,snapshot:OrchestrationShellSnapshot,source:ProjectionSource)=Unit
    override suspend fun applyShellItem(environmentId:String,item:OrchestrationShellStreamItem)=Reduction.Ignored(shell.value)
    override suspend fun focusThread(environmentId:String,threadId:String?)=Unit; override suspend fun selectProject(environmentId:String,projectId:String?)=Unit
    override suspend fun setThreadSnapshot(environmentId:String,snapshot:OrchestrationThreadDetailSnapshot,source:ProjectionSource)=Unit
    override suspend fun applyThreadItem(environmentId:String,threadId:String,item:OrchestrationThreadStreamItem)=Reduction.Ignored(focusedThread.value)
    override suspend fun clearEnvironment(environmentId:String)=Unit
    override suspend fun clearProjectionCache(environmentId:String){ cleared += environmentId }
}
