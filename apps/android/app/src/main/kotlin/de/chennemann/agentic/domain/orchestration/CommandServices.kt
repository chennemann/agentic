package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.OrchestrationCommandClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.TurnMessageInput
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject

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
}

class ThreadService(
    private val environments: EnvironmentRepository,
    private val repository: OrchestrationRepository,
    private val credentials: CredentialStore,
    private val commands: OrchestrationCommandClient,
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

    private suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult {
        val environment = requireNotNull(environments.activeEnvironment.value)
        val token = requireNotNull(credentials.read(environment.id))
        return commands.dispatch(environment.baseUrl, token, command)
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
}

class ChatService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val commands: OrchestrationCommandClient,
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
        if (threadId == null) {
            dispatch(
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
                ),
            )
        }
        val result = dispatch(
            ClientOrchestrationCommand.StartTurn(
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
            ),
        )
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

    private suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult {
        val environment = requireNotNull(environments.activeEnvironment.value)
        val token = requireNotNull(credentials.read(environment.id))
        return commands.dispatch(environment.baseUrl, token, command)
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

private const val MaxThreadTitleLength = 72

data class StartTurnResult(
    val dispatch: DispatchResult,
    val threadId: String,
)

private fun uuid(): String = UUID.randomUUID().toString()
