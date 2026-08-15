package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.ProjectDestinationRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.FilesystemBrowseEntry
import de.chennemann.agentic.t3.contract.FilesystemBrowseResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProjectDestinationServiceTest {
    @Test
    fun `browse maps transport entries into provider-neutral destinations`() = runTest {
        val client = RecordingBrowseClient(
            FilesystemBrowseResult(
                parentPath = "/srv",
                entries = listOf(FilesystemBrowseEntry("alpha", "/srv/alpha")),
            ),
        )
        val service = ProjectDestinationService(
            environments = DestinationEnvironmentRepository(environment("linux")),
            credentials = DestinationCredentials("secret"),
            rpc = client,
        )

        val listing = service.browse("/srv/alpha", enterDirectory = true)

        assertEquals("/srv/alpha/", client.partialPath)
        assertEquals("/srv/alpha/", listing.query)
        assertEquals("/srv", listing.parentPath)
        assertEquals(listOf(ProjectDestination("alpha", "/srv/alpha")), listing.destinations)
    }

    @Test
    fun `windows directory navigation uses the remote platform separator`() = runTest {
        val client = RecordingBrowseClient(FilesystemBrowseResult("C:\\", emptyList()))
        val service = ProjectDestinationService(
            environments = DestinationEnvironmentRepository(environment("windows")),
            credentials = DestinationCredentials("secret"),
            rpc = client,
        )

        service.browse("C:\\work", enterDirectory = true)

        assertEquals("C:\\work\\", client.partialPath)
    }

    @Test
    fun `missing credential prevents remote browsing`() = runTest {
        val client = RecordingBrowseClient(FilesystemBrowseResult("/", emptyList()))
        val service = ProjectDestinationService(
            environments = DestinationEnvironmentRepository(environment("linux")),
            credentials = DestinationCredentials(null),
            rpc = client,
        )

        val failure = runCatching { service.browse("~/") }.exceptionOrNull()

        assertInstanceOf(IllegalArgumentException::class.java, failure)
        assertEquals(null, client.partialPath)
    }
}

private class DestinationEnvironmentRepository(
    environment: SavedEnvironment,
) : EnvironmentRepository {
    override val environments = MutableStateFlow(listOf(environment))
    override val activeEnvironment = MutableStateFlow<SavedEnvironment?>(environment)

    override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) = Unit
    override suspend fun select(environmentId: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
    override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
}

private class DestinationCredentials(
    private val token: String?,
) : CredentialStore {
    override suspend fun read(environmentId: String): String? = token
    override suspend fun write(environmentId: String, bearerToken: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
}

private class RecordingBrowseClient(
    private val result: FilesystemBrowseResult,
) : ProjectDestinationRpcClient {
    var partialPath: String? = null

    override suspend fun browseFilesystem(baseUrl: String, bearerToken: String, partialPath: String): FilesystemBrowseResult {
        this.partialPath = partialPath
        return result
    }
}

private fun environment(platform: String) = SavedEnvironment(
    id = "env-1",
    label = "Environment",
    baseUrl = "https://example.test",
    platformOs = platform,
    platformArch = "x64",
    serverVersion = "1",
    active = true,
    lastConnectedAt = null,
)
