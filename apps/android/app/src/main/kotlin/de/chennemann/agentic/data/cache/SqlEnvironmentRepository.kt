package de.chennemann.agentic.data.cache

import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class SqlEnvironmentRepository(
    private val database: AgenticDb,
    private val dispatcher: CoroutineDispatcher,
) : EnvironmentRepository {
    private val initialEnvironments = readEnvironments()
    private val mutableEnvironments = MutableStateFlow(initialEnvironments)
    override val environments: StateFlow<List<SavedEnvironment>> = mutableEnvironments.asStateFlow()
    private val mutableActiveEnvironment = MutableStateFlow(initialEnvironments.firstOrNull { it.active })
    override val activeEnvironment: StateFlow<SavedEnvironment?> = mutableActiveEnvironment.asStateFlow()

    override suspend fun save(
        baseUrl: String,
        descriptor: ExecutionEnvironmentDescriptor,
        makeActive: Boolean,
    ) = withContext(dispatcher) {
        database.transaction {
            if (makeActive) database.agenticT3Queries.clearActiveEnvironment()
            database.agenticT3Queries.upsertEnvironment(
                descriptor.environmentId,
                descriptor.label,
                baseUrl,
                descriptor.platform.os,
                descriptor.platform.arch,
                descriptor.serverVersion,
                if (makeActive) 1L else 0L,
                System.currentTimeMillis(),
            )
        }
        refresh()
    }

    override suspend fun select(environmentId: String) = withContext(dispatcher) {
        database.transaction {
            database.agenticT3Queries.clearActiveEnvironment()
            database.agenticT3Queries.setActiveEnvironment(environmentId)
        }
        refresh()
    }

    override suspend fun remove(environmentId: String) = withContext(dispatcher) {
        database.transaction {
            database.agenticT3Queries.deleteEnvironmentProjections(environmentId)
            database.agenticT3Queries.deleteEnvironmentPreferences(environmentId)
            database.agenticT3Queries.deleteEnvironmentCommands(environmentId)
            database.agenticT3Queries.deleteEnvironment(environmentId)
        }
        refresh()
    }

    override suspend fun markConnected(
        environmentId: String,
        connectedAt: Long,
    ) = withContext(dispatcher) {
        val existing = mutableEnvironments.value.firstOrNull { it.id == environmentId } ?: return@withContext
        database.agenticT3Queries.upsertEnvironment(
            existing.id,
            existing.label,
            existing.baseUrl,
            existing.platformOs,
            existing.platformArch,
            existing.serverVersion,
            if (existing.active) 1L else 0L,
            connectedAt,
        )
        refresh()
    }

    private fun refresh() {
        val rows = readEnvironments()
        mutableEnvironments.value = rows
        mutableActiveEnvironment.value = rows.firstOrNull { it.active }
    }

    private fun readEnvironments() =
        database.agenticT3Queries.selectAllEnvironments { id, label, baseUrl, os, arch, version, active, at ->
            SavedEnvironment(
                id = id,
                label = label,
                baseUrl = baseUrl,
                platformOs = os,
                platformArch = arch,
                serverVersion = version,
                active = active == 1L,
                lastConnectedAt = at,
            )
        }.executeAsList()
}
