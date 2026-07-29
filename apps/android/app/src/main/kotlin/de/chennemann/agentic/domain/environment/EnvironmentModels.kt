package de.chennemann.agentic.domain.environment

import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import kotlinx.coroutines.flow.StateFlow

data class SavedEnvironment(
    val id: String,
    val label: String,
    val baseUrl: String,
    val platformOs: String,
    val platformArch: String,
    val serverVersion: String,
    val active: Boolean,
    val lastConnectedAt: Long?,
)

interface EnvironmentRepository {
    val environments: StateFlow<List<SavedEnvironment>>
    val activeEnvironment: StateFlow<SavedEnvironment?>

    suspend fun save(
        baseUrl: String,
        descriptor: ExecutionEnvironmentDescriptor,
        makeActive: Boolean,
    )

    suspend fun select(environmentId: String)

    suspend fun remove(environmentId: String)

    suspend fun markConnected(environmentId: String, connectedAt: Long)
}
