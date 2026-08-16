package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.TerminalRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.t3.contract.TerminalAttachEvent
import de.chennemann.agentic.t3.contract.TerminalMetadataEvent
import de.chennemann.agentic.t3.contract.ProjectScript
import de.chennemann.agentic.t3.contract.TerminalSessionSnapshot
import de.chennemann.agentic.t3.contract.TerminalSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class TerminalContext(
    val baseUrl: String,
    val token: String,
    val threadId: String,
    val cwd: String,
    val projectRoot: String = cwd,
    val worktreePath: String? = null,
)

data class ProjectScriptLaunch(
    val terminalId: String,
    val restart: Boolean,
    val initialInput: String,
)

interface TerminalSessions {
    suspend fun context(threadId: String): TerminalContext
    fun inventory(context: TerminalContext): Flow<TerminalMetadataEvent>
    fun attach(context: TerminalContext, terminalId: String): Flow<TerminalAttachEvent>
    suspend fun open(context: TerminalContext, terminalId: String): TerminalSessionSnapshot
    suspend fun write(context: TerminalContext, terminalId: String, data: String)
    suspend fun clear(context: TerminalContext, terminalId: String)
    suspend fun restart(context: TerminalContext, terminalId: String): TerminalSessionSnapshot
    suspend fun close(context: TerminalContext, terminalId: String)
    suspend fun launchScript(
        context: TerminalContext,
        terminals: Collection<TerminalSummary>,
        script: ProjectScript,
    ): Pair<String, TerminalSessionSnapshot>
}

class TerminalSessionService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val repository: OrchestrationRepository,
    private val rpc: TerminalRpcClient,
) : TerminalSessions {
    override suspend fun context(threadId: String): TerminalContext {
        val environment = requireNotNull(environments.activeEnvironment.value) { "Select an environment first." }
        val config = repository.clientConfig.value.value
        require(config?.environment?.environmentId == environment.id) { "The environment is not ready yet." }
        require(config.environment.capabilities.executionSessions) { "Terminal sessions are not supported by this server." }
        val shell = requireNotNull(repository.shell.value.value) { "The workspace is unavailable." }
        val thread = requireNotNull(shell.threads.firstOrNull { it.id == threadId }) { "The thread is no longer available." }
        val project = requireNotNull(shell.projects.firstOrNull { it.id == thread.projectId }) { "The project is no longer available." }
        val token = requireNotNull(credentials.read(environment.id)) { "Pair the environment again." }
        return TerminalContext(
            baseUrl = environment.baseUrl,
            token = token,
            threadId = threadId,
            cwd = thread.worktreePath ?: project.workspaceRoot,
            projectRoot = project.workspaceRoot,
            worktreePath = thread.worktreePath,
        )
    }

    override fun inventory(context: TerminalContext): Flow<TerminalMetadataEvent> =
        rpc.terminalMetadata(context.baseUrl, context.token).map { event ->
            when (event) {
                is TerminalMetadataEvent.Snapshot -> event.copy(terminals = event.terminals.filter { it.threadId == context.threadId })
                is TerminalMetadataEvent.Upsert -> event
                is TerminalMetadataEvent.Remove -> event
            }
        }

    override fun attach(context: TerminalContext, terminalId: String) =
        rpc.attachTerminal(context.baseUrl, context.token, context.threadId, terminalId)

    override suspend fun open(context: TerminalContext, terminalId: String) =
        rpc.openTerminal(context.baseUrl, context.token, context.threadId, terminalId, context.cwd, context.worktreePath, context.runtimeEnv())

    override suspend fun write(context: TerminalContext, terminalId: String, data: String) =
        rpc.writeTerminal(context.baseUrl, context.token, context.threadId, terminalId, data)

    override suspend fun clear(context: TerminalContext, terminalId: String) =
        rpc.clearTerminal(context.baseUrl, context.token, context.threadId, terminalId)

    override suspend fun restart(context: TerminalContext, terminalId: String) =
        rpc.restartTerminal(context.baseUrl, context.token, context.threadId, terminalId, context.cwd, context.worktreePath, context.runtimeEnv())

    override suspend fun close(context: TerminalContext, terminalId: String) =
        rpc.closeTerminal(context.baseUrl, context.token, context.threadId, terminalId)

    override suspend fun launchScript(
        context: TerminalContext,
        terminals: Collection<TerminalSummary>,
        script: ProjectScript,
    ): Pair<String, TerminalSessionSnapshot> {
        val launch = projectScriptLaunch(terminals, script)
        val snapshot = if (launch.restart) restart(context, launch.terminalId) else open(context, launch.terminalId)
        write(context, launch.terminalId, launch.initialInput)
        return launch.terminalId to snapshot
    }
}

fun projectScriptLaunch(terminals: Collection<TerminalSummary>, script: ProjectScript): ProjectScriptLaunch {
    val hasLiveTerminal = terminals.any { it.status == "running" || it.status == "starting" }
    val terminalId = if (hasLiveTerminal) nextTerminalId(terminals) else "term-1"
    return ProjectScriptLaunch(
        terminalId = terminalId,
        restart = terminals.any { it.terminalId == terminalId },
        initialInput = "${script.command}\r",
    )
}

private fun TerminalContext.runtimeEnv(): Map<String, String> = buildMap {
    put("T3CODE_PROJECT_ROOT", projectRoot)
    worktreePath?.let { put("T3CODE_WORKTREE_PATH", it) }
}

fun preferredTerminal(terminals: List<TerminalSummary>): String? =
    terminals.firstOrNull { it.terminalId == "term-1" && it.status == "running" }?.terminalId
        ?: terminals.firstOrNull { it.status == "running" }?.terminalId
        ?: terminals.firstOrNull()?.terminalId

fun nextTerminalId(terminals: Collection<TerminalSummary>): String {
    val used = terminals.mapTo(mutableSetOf()) { it.terminalId }
    return generateSequence(1) { it + 1 }.map { "term-$it" }.first { it !in used }
}
