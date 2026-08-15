package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.ThreadWorkspaceRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository

data class ThreadWorkspaceChoice(
    val id: String,
    val label: String,
    val description: String,
    val workspace: NewThreadWorkspace,
)

interface ThreadWorkspaceBrowser {
    suspend fun choices(projectId: String): List<ThreadWorkspaceChoice>
}

class ThreadWorkspaceService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val repository: OrchestrationRepository,
    private val rpc: ThreadWorkspaceRpcClient,
) : ThreadWorkspaceBrowser {
    override suspend fun choices(projectId: String): List<ThreadWorkspaceChoice> {
        val environment = requireNotNull(environments.activeEnvironment.value) { "Select an environment first." }
        val config = repository.clientConfig.value.value
        require(config?.environment?.environmentId == environment.id) { "The environment is not ready yet." }
        if (!config.environment.capabilities.threadWorktrees) return listOf(InPlaceChoice)
        val project = requireNotNull(repository.shell.value.value?.projects?.firstOrNull { it.id == projectId }) {
            "The project is no longer available."
        }
        val token = requireNotNull(credentials.read(environment.id)) {
            "The environment credential is unavailable. Pair the environment again."
        }
        val refs = rpc.listRefs(environment.baseUrl, token, project.workspaceRoot)
        if (!refs.isRepo) return listOf(InPlaceChoice)
        val local = refs.refs.filterNot { it.isRemote }
        val existing = local.filter { it.worktreePath != null }.map { ref ->
            ThreadWorkspaceChoice(
                id = "existing:${ref.worktreePath}",
                label = if (ref.current) "Local checkout" else "Worktree: ${ref.name}",
                description = ref.worktreePath.orEmpty(),
                workspace = if (ref.current) {
                    NewThreadWorkspace.InPlace(ref.name)
                } else {
                    NewThreadWorkspace.Existing(ref.name, requireNotNull(ref.worktreePath))
                },
            )
        }
        val bases = refs.refs
            .filter { it.current || it.isDefault || (!it.isRemote && it.worktreePath == null) }
            .distinctBy { it.name }
            .map { ref ->
                ThreadWorkspaceChoice(
                    id = "new:${ref.name}",
                    label = "New worktree from ${ref.name}",
                    description = "Create an isolated checkout for this thread",
                    workspace = NewThreadWorkspace.CreateWorktree(
                        projectCwd = project.workspaceRoot,
                        baseBranch = ref.name,
                        startFromOrigin = refs.hasPrimaryRemote && !ref.isRemote,
                    ),
                )
            }
        return (listOf(InPlaceChoice) + existing.filterNot { it.id == "in-place" } + bases)
            .distinctBy { it.id }
    }

    private companion object {
        val InPlaceChoice = ThreadWorkspaceChoice(
            id = "in-place",
            label = "Local checkout",
            description = "Use the project's current checkout",
            workspace = NewThreadWorkspace.InPlace(),
        )
    }
}
