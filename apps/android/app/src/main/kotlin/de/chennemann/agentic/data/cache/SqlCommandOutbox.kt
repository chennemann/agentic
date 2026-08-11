package de.chennemann.agentic.data.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.db.Command_outbox
import de.chennemann.agentic.domain.orchestration.CommandOutbox
import de.chennemann.agentic.domain.orchestration.OutboxCommand
import de.chennemann.agentic.domain.orchestration.OutboxCommandStatus
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.T3CommandJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlCommandOutbox(
    private val database: AgenticDb,
    private val dispatcher: CoroutineDispatcher,
) : CommandOutbox {
    override fun observe(environmentId: String): Flow<List<OutboxCommand>> =
        database.agenticT3Queries.selectOutbox(environmentId).asFlow().mapToList(dispatcher).map(::decode)

    override suspend fun enqueue(environmentId: String, command: ClientOrchestrationCommand) =
        withContext(dispatcher) {
            database.agenticT3Queries.enqueueCommand(
                environment_id = environmentId,
                command_id = command.commandId,
                command_json = T3CommandJson.encodeToString(ClientOrchestrationCommand.serializer(), command),
                created_at = System.currentTimeMillis(),
            )
            Unit
        }

    override suspend fun commands(environmentId: String): List<OutboxCommand> = withContext(dispatcher) {
        decode(database.agenticT3Queries.selectOutbox(environmentId).executeAsList())
    }

    override suspend fun markPending(environmentId: String, commandId: String) = withContext(dispatcher) {
        database.agenticT3Queries.markCommandPending(environmentId, commandId)
        Unit
    }

    override suspend fun markFailed(environmentId: String, commandId: String, error: String) =
        withContext(dispatcher) {
            database.agenticT3Queries.markCommandFailed(error, environmentId, commandId)
            Unit
        }

    override suspend fun remove(environmentId: String, commandId: String) = withContext(dispatcher) {
        database.agenticT3Queries.deleteCommand(environmentId, commandId)
        Unit
    }

    private fun decode(rows: List<Command_outbox>): List<OutboxCommand> = rows.map { row ->
        OutboxCommand(
            environmentId = row.environment_id,
            command = T3CommandJson.decodeFromString(ClientOrchestrationCommand.serializer(), row.command_json),
            status = if (row.status == "failed") OutboxCommandStatus.FAILED else OutboxCommandStatus.PENDING,
            attemptCount = row.attempt_count,
            lastError = row.last_error,
        )
    }
}
