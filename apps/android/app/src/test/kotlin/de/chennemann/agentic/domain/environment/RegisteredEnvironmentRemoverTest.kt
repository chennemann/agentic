package de.chennemann.agentic.domain.environment

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RegisteredEnvironmentRemoverTest {
    @Test
    fun `removing active environment clears only its data and selects another environment`() = runTest {
        val environments = RemovalEnvironmentRepository(listOf(environment("one", active = true), environment("two")))
        val credentials = RecordingCredentials()
        val cleared = mutableListOf<String>()
        val remover = RegisteredEnvironmentRemover(environments, credentials, cleared::add)

        remover.remove("one")

        assertEquals(listOf("one"), credentials.removed)
        assertEquals(listOf("one"), cleared)
        assertEquals(listOf("two"), environments.environments.value.map { it.id })
        assertEquals("two", environments.activeEnvironment.value?.id)
    }

    @Test
    fun `credential failure leaves registered environment and caches untouched`() = runTest {
        val environments = RemovalEnvironmentRepository(listOf(environment("one", active = true)))
        val credentials = RecordingCredentials(failRemoval = true)
        val cleared = mutableListOf<String>()
        val remover = RegisteredEnvironmentRemover(environments, credentials, cleared::add)

        assertInstanceOf(IllegalStateException::class.java, runCatching { remover.remove("one") }.exceptionOrNull())

        assertEquals(listOf("one"), environments.environments.value.map { it.id })
        assertEquals(emptyList<String>(), cleared)
    }

    @Test
    fun `unknown environment is rejected before deleting any data`() = runTest {
        val environments = RemovalEnvironmentRepository(listOf(environment("one", active = true)))
        val credentials = RecordingCredentials()
        val cleared = mutableListOf<String>()
        val remover = RegisteredEnvironmentRemover(environments, credentials, cleared::add)

        assertInstanceOf(IllegalArgumentException::class.java, runCatching { remover.remove("missing") }.exceptionOrNull())

        assertFalse(credentials.removed.isNotEmpty())
        assertEquals(emptyList<String>(), cleared)
    }
}

private class RecordingCredentials(private val failRemoval: Boolean = false) : CredentialStore {
    val removed = mutableListOf<String>()
    override suspend fun read(environmentId: String): String? = null
    override suspend fun write(environmentId: String, bearerToken: String) = Unit
    override suspend fun remove(environmentId: String) {
        if (failRemoval) error("Keystore unavailable")
        removed += environmentId
    }
}

private class RemovalEnvironmentRepository(initial: List<SavedEnvironment>) : EnvironmentRepository {
    private val mutableEnvironments = MutableStateFlow(initial)
    private val mutableActive = MutableStateFlow(initial.firstOrNull { it.active })
    override val environments: StateFlow<List<SavedEnvironment>> = mutableEnvironments
    override val activeEnvironment: StateFlow<SavedEnvironment?> = mutableActive

    override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) = Unit
    override suspend fun select(environmentId: String) {
        mutableEnvironments.value = mutableEnvironments.value.map { it.copy(active = it.id == environmentId) }
        mutableActive.value = mutableEnvironments.value.firstOrNull { it.active }
    }
    override suspend fun remove(environmentId: String) {
        mutableEnvironments.value = mutableEnvironments.value.filterNot { it.id == environmentId }
        if (mutableActive.value?.id == environmentId) mutableActive.value = null
    }
    override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
}

private fun environment(id: String, active: Boolean = false) = SavedEnvironment(
    id = id,
    label = "Environment $id",
    baseUrl = "https://$id.example.test/",
    platformOs = "provider-os-$id",
    platformArch = "provider-arch-$id",
    serverVersion = "provider-version-$id",
    active = active,
    lastConnectedAt = null,
)
