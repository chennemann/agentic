package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.data.t3.redactTransportText
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class StartedProject(val projectId: String, val notice: String? = null)

interface ProjectStarter {
    suspend fun createNew(name: String): StartedProject
    suspend fun useScratch(): StartedProject
}

class ProjectStarterService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val repository: OrchestrationRepository,
    private val rpc: T3RpcClient,
) : ProjectStarter {
    override suspend fun createNew(name: String): StartedProject {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= 200) { "Enter a project name of up to 200 characters." }
        return start(scratch = false) { baseUrl, token ->
            val result = rpc.createNewProject(baseUrl, token, normalized)
            StartedProject(result.projectId, result.commitError?.let {
                "Project created. The initial commit failed: ${redactTransportText(it, listOf(token))}"
            })
        }
    }

    override suspend fun useScratch(): StartedProject = start(scratch = true) { baseUrl, token ->
        StartedProject(rpc.ensureScratch(baseUrl, token).projectId)
    }

    private suspend fun start(scratch: Boolean, operation: suspend (String, String) -> StartedProject): StartedProject {
        val environment = requireNotNull(environments.activeEnvironment.value) { "Select an environment first." }
        val config = requireNotNull(repository.clientConfig.value.value) { "The environment is not ready yet." }
        require(config.environment.environmentId == environment.id) { "The environment is not ready yet." }
        require(if (scratch) config.scratchWorkspaceRoot != null else config.newProjectsRoot != null) {
            "This server does not support this project action."
        }
        val token = requireNotNull(credentials.read(environment.id)) { "Pair this environment again." }
        val result = operation(environment.baseUrl, token)
        val observed = withTimeoutOrNull(15_000) {
            repository.shell.first {
                environments.activeEnvironment.value?.id != environment.id ||
                    it.value?.projects?.any { project -> project.id == result.projectId } == true
            }
            true
        }
        check(environments.activeEnvironment.value?.id == environment.id) { "The environment changed while creating the project." }
        check(observed == true) { "Project created. Reconnect to see it in the project list." }
        return result
    }
}
