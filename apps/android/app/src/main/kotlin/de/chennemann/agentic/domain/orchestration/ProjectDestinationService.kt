package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.ProjectDestinationRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository

data class ProjectDestination(
    val name: String,
    val fullPath: String,
)

data class ProjectDestinationListing(
    val query: String,
    val parentPath: String,
    val destinations: List<ProjectDestination>,
)

interface ProjectDestinationBrowser {
    suspend fun browse(
        partialPath: String,
        enterDirectory: Boolean = false,
    ): ProjectDestinationListing
}

class ProjectDestinationService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val rpc: ProjectDestinationRpcClient,
) : ProjectDestinationBrowser {
    override suspend fun browse(
        partialPath: String,
        enterDirectory: Boolean,
    ): ProjectDestinationListing {
        val environment = requireNotNull(environments.activeEnvironment.value) {
            "Select an environment before browsing folders."
        }
        val token = requireNotNull(credentials.read(environment.id)) {
            "The environment credential is unavailable. Pair the environment again."
        }
        val query = if (enterDirectory) {
            partialPath.trimEnd('/', '\\') + if (environment.platformOs.equals("windows", true)) "\\" else "/"
        } else {
            partialPath
        }
        val result = rpc.browseFilesystem(environment.baseUrl, token, query)
        return ProjectDestinationListing(
            query = query,
            parentPath = result.parentPath,
            destinations = result.entries.map { ProjectDestination(it.name, it.fullPath) },
        )
    }
}
