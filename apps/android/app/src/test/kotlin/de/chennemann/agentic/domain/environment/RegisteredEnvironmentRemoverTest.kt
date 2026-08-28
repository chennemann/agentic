package de.chennemann.agentic.domain.environment

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.auth.ClientCertificateStore
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
    fun `removing cleartext environment does not require client certificate configuration`() = runTest {
        val environments = RemovalEnvironmentRepository(
            listOf(environment("local", active = true, baseUrl = "http://192.168.1.10:3000/")),
        )
        val credentials = RecordingCredentials()
        val certificates = RecordingClientCertificates(rejectCleartext = true)
        val remover = RegisteredEnvironmentRemover(environments, credentials, {}, certificates)

        remover.remove("local")

        assertEquals(emptyList<SavedEnvironment>(), environments.environments.value)
        assertEquals(emptyList<String>(), certificates.removed)
    }

    @Test
    fun `removing active environment clears only its data and selects another environment`() = runTest {
        val environments = RemovalEnvironmentRepository(listOf(environment("one", active = true), environment("two")))
        val credentials = RecordingCredentials()
        val certificates = RecordingClientCertificates()
        val cleared = mutableListOf<String>()
        val remover = RegisteredEnvironmentRemover(environments, credentials, cleared::add, certificates)

        remover.remove("one")

        assertEquals(listOf("one"), credentials.removed)
        assertEquals(listOf("one"), cleared)
        assertEquals(listOf("https://one.example.test/"), certificates.removed)
        assertEquals(listOf("two"), environments.environments.value.map { it.id })
        assertEquals("two", environments.activeEnvironment.value?.id)
    }

    @Test
    fun `credential failure leaves registered environment and caches untouched`() = runTest {
        val environments = RemovalEnvironmentRepository(listOf(environment("one", active = true)))
        val credentials = RecordingCredentials(failRemoval = true)
        val certificates = RecordingClientCertificates()
        val cleared = mutableListOf<String>()
        val remover = RegisteredEnvironmentRemover(environments, credentials, cleared::add, certificates)

        assertInstanceOf(IllegalStateException::class.java, runCatching { remover.remove("one") }.exceptionOrNull())

        assertEquals(listOf("one"), environments.environments.value.map { it.id })
        assertEquals(emptyList<String>(), cleared)
        assertEquals(emptyList<String>(), certificates.removed)
    }

    @Test
    fun `unknown environment is rejected before deleting any data`() = runTest {
        val environments = RemovalEnvironmentRepository(listOf(environment("one", active = true)))
        val credentials = RecordingCredentials()
        val certificates = RecordingClientCertificates()
        val cleared = mutableListOf<String>()
        val remover = RegisteredEnvironmentRemover(environments, credentials, cleared::add, certificates)

        assertInstanceOf(IllegalArgumentException::class.java, runCatching { remover.remove("missing") }.exceptionOrNull())

        assertFalse(credentials.removed.isNotEmpty())
        assertEquals(emptyList<String>(), cleared)
        assertEquals(emptyList<String>(), certificates.removed)
    }
}

private class RecordingClientCertificates(
    private val rejectCleartext: Boolean = false,
) : ClientCertificateStore {
    val removed = mutableListOf<String>()

    override fun aliasFor(baseUrl: String): String? = "configured-alias".takeIf {
        baseUrl.startsWith("https://")
    }

    override fun setAlias(baseUrl: String, alias: String?) {
        if (rejectCleartext && baseUrl.startsWith("http://")) {
            throw IllegalArgumentException("Client certificates require an HTTPS server URL.")
        }
        if (alias == null) removed += baseUrl
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

private fun environment(
    id: String,
    active: Boolean = false,
    baseUrl: String = "https://$id.example.test/",
) = SavedEnvironment(
    id = id,
    label = "Environment $id",
    baseUrl = baseUrl,
    platformOs = "provider-os-$id",
    platformArch = "provider-arch-$id",
    serverVersion = "provider-version-$id",
    active = active,
    lastConnectedAt = null,
)
