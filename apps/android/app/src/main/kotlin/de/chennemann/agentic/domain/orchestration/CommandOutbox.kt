package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class OutboxCommandStatus {
    PENDING,
    FAILED,
}

data class OutboxCommand(
    val environmentId: String,
    val command: ClientOrchestrationCommand,
    val status: OutboxCommandStatus,
    val attemptCount: Long,
    val lastError: String?,
)

data class DurableWorkStatus(
    val commandId: String,
    val label: String,
    val threadId: String?,
    val status: OutboxCommandStatus,
    val attemptCount: Long,
    val errorMessage: String?,
) {
    val awaitsReplay: Boolean get() = status == OutboxCommandStatus.FAILED
}

interface CommandOutbox {
    fun observe(environmentId: String): Flow<List<OutboxCommand>>

    suspend fun enqueue(environmentId: String, command: ClientOrchestrationCommand)

    suspend fun commands(environmentId: String): List<OutboxCommand>

    suspend fun markPending(environmentId: String, commandId: String)

    suspend fun markFailed(environmentId: String, commandId: String, error: String)

    suspend fun remove(environmentId: String, commandId: String)

    fun observeStatuses(environmentId: String): Flow<List<DurableWorkStatus>> =
        observe(environmentId).map { entries -> entries.map(OutboxCommand::toStatus) }

    suspend fun hasThreadWork(environmentId: String, threadId: String, excludeDeletion: Boolean = false): Boolean =
        commands(environmentId).any {
            it.command.threadTarget() == threadId && (!excludeDeletion || it.command !is ClientOrchestrationCommand.DeleteThread)
        }
}

private fun OutboxCommand.toStatus() = DurableWorkStatus(
    commandId = command.commandId,
    label = command.safeLabel(),
    threadId = command.threadTarget(),
    status = status,
    attemptCount = attemptCount,
    errorMessage = lastError,
)

private fun ClientOrchestrationCommand.threadTarget(): String? = when (this) {
    is ClientOrchestrationCommand.CreateThread -> threadId
    is ClientOrchestrationCommand.StartTurn -> threadId
    is ClientOrchestrationCommand.InterruptTurn -> threadId
    is ClientOrchestrationCommand.StopSession -> threadId
    is ClientOrchestrationCommand.UpdateThreadMetadata -> threadId
    is ClientOrchestrationCommand.ArchiveThread -> threadId
    is ClientOrchestrationCommand.DeleteThread -> threadId
    is ClientOrchestrationCommand.UnarchiveThread -> threadId
    is ClientOrchestrationCommand.SettleThread -> threadId
    is ClientOrchestrationCommand.UnsettleThread -> threadId
    is ClientOrchestrationCommand.SnoozeThread -> threadId
    is ClientOrchestrationCommand.UnsnoozeThread -> threadId
    is ClientOrchestrationCommand.SetRuntimeMode -> threadId
    is ClientOrchestrationCommand.SetInteractionMode -> threadId
    is ClientOrchestrationCommand.RespondToApproval -> threadId
    is ClientOrchestrationCommand.RespondToUserInput -> threadId
    is ClientOrchestrationCommand.CreateProject,
    is ClientOrchestrationCommand.UpdateProjectMetadata,
    is ClientOrchestrationCommand.DeleteProject,
    -> null
}

private fun ClientOrchestrationCommand.safeLabel(): String = when (this) {
    is ClientOrchestrationCommand.StartTurn -> "Send message"
    is ClientOrchestrationCommand.CreateThread -> "Create thread"
    is ClientOrchestrationCommand.DeleteThread -> "Delete thread"
    is ClientOrchestrationCommand.StopSession -> "Stop session"
    is ClientOrchestrationCommand.InterruptTurn -> "Stop turn"
    is ClientOrchestrationCommand.CreateProject -> "Create project"
    is ClientOrchestrationCommand.UpdateProjectMetadata -> "Update project"
    is ClientOrchestrationCommand.DeleteProject -> "Delete project"
    else -> "Update thread"
}

interface CommandDispatcher {
    suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult

    suspend fun dispatchInOrder(commands: List<ClientOrchestrationCommand>): List<DispatchResult> =
        commands.map { dispatch(it) }
}

fun interface PendingCommandReplayer {
    suspend fun replay(environment: SavedEnvironment)
}

class DurableCommandDispatcher(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val client: T3RpcClient,
    private val outbox: CommandOutbox,
) : CommandDispatcher, PendingCommandReplayer {
    override suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult {
        return dispatchInOrder(listOf(command)).single()
    }

    override suspend fun dispatchInOrder(
        commands: List<ClientOrchestrationCommand>,
    ): List<DispatchResult> {
        val environment = requireNotNull(environments.activeEnvironment.value)
        commands.forEach { outbox.enqueue(environment.id, it) }
        return commands.map { send(environment, it) }
    }

    override suspend fun replay(environment: SavedEnvironment) {
        for (entry in outbox.commands(environment.id)) {
            try {
                send(environment, entry.command)
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Exception) {
                break
            }
        }
    }

    private suspend fun send(
        environment: SavedEnvironment,
        command: ClientOrchestrationCommand,
    ): DispatchResult {
        outbox.markPending(environment.id, command.commandId)
        return try {
            val token = requireNotNull(credentials.read(environment.id))
            client.dispatch(environment.baseUrl, token, command).also {
                outbox.remove(environment.id, command.commandId)
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            outbox.markFailed(
                environment.id,
                command.commandId,
                cause.message ?: cause::class.simpleName ?: "Command dispatch failed",
            )
            throw cause
        }
    }
}
