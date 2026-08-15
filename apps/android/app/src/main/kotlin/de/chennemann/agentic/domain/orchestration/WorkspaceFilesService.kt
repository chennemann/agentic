package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.WorkspaceFilesRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository

data class WorkspaceFileEntry(val path: String, val isDirectory: Boolean)

data class WorkspaceFileListing(
    val cwd: String,
    val entries: List<WorkspaceFileEntry>,
    val truncated: Boolean,
)

interface WorkspaceFilesBrowser {
    suspend fun list(threadId: String): WorkspaceFileListing
}

class WorkspaceFilesService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val repository: OrchestrationRepository,
    private val rpc: WorkspaceFilesRpcClient,
) : WorkspaceFilesBrowser {
    override suspend fun list(threadId: String): WorkspaceFileListing {
        val environment = requireNotNull(environments.activeEnvironment.value) { "Select an environment first." }
        val config = repository.clientConfig.value.value
        require(config?.environment?.environmentId == environment.id) { "The environment is not ready yet." }
        require(config.environment.capabilities.workspaceFiles) { "Workspace files are not supported by this server." }
        val shell = requireNotNull(repository.shell.value.value) { "The workspace is unavailable." }
        val thread = requireNotNull(shell.threads.firstOrNull { it.id == threadId }) { "The thread is no longer available." }
        val project = requireNotNull(shell.projects.firstOrNull { it.id == thread.projectId }) { "The project is no longer available." }
        val cwd = thread.worktreePath ?: project.workspaceRoot
        require(cwd.isNotBlank()) { "This thread does not have an active workspace path." }
        val token = requireNotNull(credentials.read(environment.id)) {
            "The environment credential is unavailable. Pair the environment again."
        }
        val result = rpc.listEntries(environment.baseUrl, token, cwd)
        return WorkspaceFileListing(
            cwd = cwd,
            entries = result.entries.map { WorkspaceFileEntry(it.path, it.kind == "directory") },
            truncated = result.truncated,
        )
    }
}
