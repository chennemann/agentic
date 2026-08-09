package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.TurnMessageInput
import de.chennemann.agentic.t3.contract.SourceProposedPlan
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

interface ThreadActions {
    suspend fun selectProject(projectId: String?)

    suspend fun selectThread(threadId: String?)

    suspend fun rename(
        threadId: String,
        title: String,
        modelSelection: ModelSelection,
    ): DispatchResult

    suspend fun archive(threadId: String): DispatchResult

    suspend fun unarchive(threadId: String): DispatchResult

    suspend fun settle(threadId: String): DispatchResult

    suspend fun unsettle(threadId: String): DispatchResult

    suspend fun snooze(threadId: String, snoozedUntil: String): DispatchResult = error("Thread snooze is not supported.")

    suspend fun wake(threadId: String): DispatchResult = error("Thread wake is not supported.")

    suspend fun delete(threadId: String): DispatchResult = error("Permanent thread deletion is not supported.")
}

class ThreadService(
    private val environments: EnvironmentRepository,
    private val repository: OrchestrationRepository,
    private val commands: CommandDispatcher,
) : ThreadActions {
    override suspend fun selectProject(projectId: String?) {
        val environment = requireNotNull(environments.activeEnvironment.value)
        repository.selectProject(environment.id, projectId)
        repository.focusThread(environment.id, null)
    }

    override suspend fun selectThread(threadId: String?) {
        val environment = requireNotNull(environments.activeEnvironment.value)
        repository.focusThread(environment.id, threadId)
    }

    override suspend fun rename(
        threadId: String,
        title: String,
        modelSelection: ModelSelection,
    ): DispatchResult = dispatch(
        ClientOrchestrationCommand.UpdateThreadMetadata(
            commandId = uuid(),
            threadId = threadId,
            title = title.trim(),
            modelSelection = modelSelection,
        ),
    )

    override suspend fun archive(threadId: String): DispatchResult = dispatch(
        ClientOrchestrationCommand.ArchiveThread(uuid(), threadId),
    )

    override suspend fun unarchive(threadId: String): DispatchResult = dispatch(
        ClientOrchestrationCommand.UnarchiveThread(uuid(), threadId),
    )

    override suspend fun settle(threadId: String): DispatchResult = dispatch(
        ClientOrchestrationCommand.SettleThread(uuid(), threadId),
    )

    override suspend fun unsettle(threadId: String): DispatchResult = dispatch(
        ClientOrchestrationCommand.UnsettleThread(uuid(), threadId),
    )

    override suspend fun snooze(threadId: String, snoozedUntil: String): DispatchResult = dispatch(
        ClientOrchestrationCommand.SnoozeThread(uuid(), threadId, snoozedUntil),
    )

    override suspend fun wake(threadId: String): DispatchResult = dispatch(
        ClientOrchestrationCommand.UnsnoozeThread(uuid(), threadId),
    )

    override suspend fun delete(threadId: String): DispatchResult = dispatch(
        ClientOrchestrationCommand.DeleteThread(uuid(), threadId),
    )

    private suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult {
        return commands.dispatch(command)
    }
}

interface ChatActions {
    suspend fun startTurn(
        threadId: String?,
        projectId: String,
        prompt: String,
        modelSelection: ModelSelection,
        interactionMode: String,
        runtimeMode: String,
    ): StartTurnResult

    suspend fun interrupt(
        threadId: String,
        turnId: String,
    ): DispatchResult

    suspend fun respondToApproval(
        threadId: String,
        requestId: String,
        decision: String,
    ): DispatchResult

    suspend fun respondToUserInput(
        threadId: String,
        requestId: String,
        answers: JsonObject,
    ): DispatchResult

    suspend fun setInteractionMode(
        threadId: String,
        interactionMode: String,
    ): DispatchResult

    suspend fun setRuntimeMode(
        threadId: String,
        runtimeMode: String,
    ): DispatchResult

    suspend fun continuePlan(
        threadId: String,
        planId: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
    ): DispatchResult = error("Plan continuation is not supported.")

    suspend fun terminateSession(threadId: String): DispatchResult = error("Session termination is not supported.")
}

interface ProjectActions {
    suspend fun create(source: String): String

    suspend fun rename(projectId: String, title: String)

    suspend fun remove(projectId: String): String?
}

class ProjectService(
    private val repository: OrchestrationRepository,
    private val commands: CommandDispatcher,
) : ProjectActions {
    override suspend fun create(source: String): String {
        val normalized = validateProjectSource(source)
        val projectId = uuid()
        commands.dispatch(
            ClientOrchestrationCommand.CreateProject(
                commandId = uuid(),
                projectId = projectId,
                title = projectTitle(normalized),
                workspaceRoot = normalized,
                createdAt = Instant.now().toString(),
            ),
        )
        withTimeout(ProjectConfirmationTimeoutMillis) {
            repository.shell
                .first { state -> state.value?.projects?.any { it.id == projectId } == true }
        }
        return projectId
    }

    override suspend fun rename(projectId: String, title: String) {
        val normalized = validateProjectTitle(title)
        val project = requireNotNull(repository.shell.value.value?.projects?.firstOrNull { it.id == projectId }) {
            "Project is no longer available."
        }
        if (project.title == normalized) return
        commands.dispatch(
            ClientOrchestrationCommand.UpdateProjectMetadata(
                commandId = uuid(),
                projectId = projectId,
                title = normalized,
            ),
        )
        withTimeout(ProjectConfirmationTimeoutMillis) {
            repository.shell.first { state ->
                state.value?.projects?.firstOrNull { it.id == projectId }?.title == normalized
            }
        }
    }

    override suspend fun remove(projectId: String): String? {
        val projects = repository.shell.value.value?.projects.orEmpty()
        require(projects.any { it.id == projectId }) { "Project is no longer available in this environment." }
        commands.dispatch(
            ClientOrchestrationCommand.DeleteProject(
                commandId = uuid(),
                projectId = projectId,
                force = false,
            ),
        )
        withTimeout(ProjectConfirmationTimeoutMillis) {
            repository.shell.first { state -> state.value?.projects?.none { it.id == projectId } == true }
        }
        return repository.shell.value.value?.projects?.firstOrNull()?.id
    }
}

class ChatService(
    private val commands: CommandDispatcher,
) : ChatActions {
    override suspend fun startTurn(
        threadId: String?,
        projectId: String,
        prompt: String,
        modelSelection: ModelSelection,
        interactionMode: String,
        runtimeMode: String,
    ): StartTurnResult {
        val now = Instant.now().toString()
        val targetThreadId = threadId ?: uuid()
        val titleSeed = deriveThreadTitle(prompt)
        val create = if (threadId == null) {
            ClientOrchestrationCommand.CreateThread(
                commandId = uuid(),
                threadId = targetThreadId,
                projectId = projectId,
                title = titleSeed,
                modelSelection = modelSelection,
                interactionMode = interactionMode,
                runtimeMode = runtimeMode,
                branch = null,
                worktreePath = null,
                createdAt = now,
            )
        } else {
            null
        }
        val start = ClientOrchestrationCommand.StartTurn(
            commandId = uuid(),
            threadId = targetThreadId,
            message = TurnMessageInput(
                messageId = uuid(),
                text = prompt,
            ),
            modelSelection = modelSelection,
            interactionMode = interactionMode,
            runtimeMode = runtimeMode,
            createdAt = now,
        )
        val result = commands.dispatchInOrder(listOfNotNull(create, start)).last()
        return StartTurnResult(result, targetThreadId)
    }

    override suspend fun interrupt(
        threadId: String,
        turnId: String,
    ): DispatchResult = dispatch(
        ClientOrchestrationCommand.InterruptTurn(
            commandId = uuid(),
            threadId = threadId,
            turnId = turnId,
            createdAt = Instant.now().toString(),
        ),
    )

    override suspend fun respondToApproval(
        threadId: String,
        requestId: String,
        decision: String,
    ): DispatchResult = dispatch(
        ClientOrchestrationCommand.RespondToApproval(
            commandId = uuid(),
            threadId = threadId,
            requestId = requestId,
            decision = decision,
            createdAt = Instant.now().toString(),
        ),
    )

    override suspend fun respondToUserInput(
        threadId: String,
        requestId: String,
        answers: JsonObject,
    ): DispatchResult = dispatch(
        ClientOrchestrationCommand.RespondToUserInput(
            commandId = uuid(),
            threadId = threadId,
            requestId = requestId,
            answers = answers,
            createdAt = Instant.now().toString(),
        ),
    )

    override suspend fun setInteractionMode(
        threadId: String,
        interactionMode: String,
    ): DispatchResult = dispatch(
        ClientOrchestrationCommand.SetInteractionMode(
            commandId = uuid(),
            threadId = threadId,
            interactionMode = interactionMode,
            createdAt = Instant.now().toString(),
        ),
    )

    override suspend fun setRuntimeMode(
        threadId: String,
        runtimeMode: String,
    ): DispatchResult = dispatch(
        ClientOrchestrationCommand.SetRuntimeMode(
            commandId = uuid(),
            threadId = threadId,
            runtimeMode = runtimeMode,
            createdAt = Instant.now().toString(),
        ),
    )

    override suspend fun continuePlan(
        threadId: String,
        planId: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
    ): DispatchResult = dispatch(
        ClientOrchestrationCommand.StartTurn(
            commandId = uuid(),
            threadId = threadId,
            message = TurnMessageInput(
                messageId = uuid(),
                text = "Implement the accepted plan.",
            ),
            modelSelection = modelSelection,
            interactionMode = "default",
            runtimeMode = runtimeMode,
            createdAt = Instant.now().toString(),
            sourceProposedPlan = SourceProposedPlan(threadId, planId),
        ),
    )

    override suspend fun terminateSession(threadId: String): DispatchResult = dispatch(
        ClientOrchestrationCommand.StopSession(
            commandId = uuid(),
            threadId = threadId,
            createdAt = Instant.now().toString(),
        ),
    )

    private suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult {
        return commands.dispatch(command)
    }
}

private fun deriveThreadTitle(prompt: String): String {
    val compact = prompt.trim().replace(Regex("\\s+"), " ")
    if (compact.isEmpty()) return "New thread"
    return if (compact.length <= MaxThreadTitleLength) {
        compact
    } else {
        compact.take(MaxThreadTitleLength - 3).trimEnd() + "..."
    }
}

internal fun validateProjectSource(source: String): String {
    val normalized = source.trim()
    require(normalized.isNotEmpty()) { "Enter a server path or repository URL." }
    val isAbsolutePath = normalized.startsWith("/") || WindowsAbsolutePath.matches(normalized)
    val isRepositoryUrl = RepositoryUrlPrefixes.any(normalized::startsWith) || ScpRepositoryUrl.matches(normalized)
    require(isAbsolutePath || isRepositoryUrl) {
        "Use an absolute server path or a repository URL."
    }
    return normalized
}

internal fun validateProjectTitle(title: String): String {
    val normalized = title.trim().replace(Regex("\\s+"), " ")
    require(normalized.isNotEmpty()) { "Enter a project name." }
    require(normalized.length <= MaxProjectTitleLength) {
        "Project names must be $MaxProjectTitleLength characters or fewer."
    }
    require(normalized.none { it.isISOControl() }) { "Project names cannot contain control characters." }
    return normalized
}

private fun projectTitle(source: String): String = source
    .trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .removeSuffix(".git")
    .ifBlank { "New project" }

private const val MaxThreadTitleLength = 72
private const val ProjectConfirmationTimeoutMillis = 10_000L
private const val MaxProjectTitleLength = 120
private val WindowsAbsolutePath = Regex("^[A-Za-z]:[\\\\/].+")
private val ScpRepositoryUrl = Regex("^[^@\\s]+@[^:\\s]+:.+")
private val RepositoryUrlPrefixes = listOf("https://", "http://", "ssh://", "git://")

data class StartTurnResult(
    val dispatch: DispatchResult,
    val threadId: String,
)

private fun uuid(): String = UUID.randomUUID().toString()
