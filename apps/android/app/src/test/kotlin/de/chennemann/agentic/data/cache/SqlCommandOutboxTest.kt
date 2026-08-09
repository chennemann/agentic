package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.OrchestrationCommandClient
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.DurableCommandDispatcher
import de.chennemann.agentic.domain.orchestration.OutboxCommandStatus
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SqlCommandOutboxTest {
    @Test
    fun `provider neutral status survives failure recreation and disappears on acknowledgement`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val outbox = SqlCommandOutbox(database, StandardTestDispatcher(testScheduler))
        outbox.enqueue("environment", ClientOrchestrationCommand.ArchiveThread("command", "thread-1"))
        assertEquals("Update thread", outbox.observeStatuses("environment").first().single().label)
        outbox.markFailed("environment", "command", "Network unavailable")

        val recreated = SqlCommandOutbox(database, StandardTestDispatcher(testScheduler))
        val failed = recreated.observeStatuses("environment").first().single()
        assertEquals("thread-1", failed.threadId)
        assertEquals("Network unavailable", failed.errorMessage)
        assertTrue(failed.awaitsReplay)
        recreated.remove("environment", "command")
        assertTrue(recreated.observeStatuses("environment").first().isEmpty())
        driver.close()
    }

    @Test
    fun `ordered command group is fully durable before its first send`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val io = StandardTestDispatcher(testScheduler)
        val environment = savedEnvironment()
        val outbox = SqlCommandOutbox(database, io)
        val dispatcher = DurableCommandDispatcher(
            environments = OutboxEnvironmentRepository(environment),
            credentials = OutboxCredentialStore,
            client = RecoveringClient(),
            outbox = outbox,
        )
        val commands = listOf(
            ClientOrchestrationCommand.ArchiveThread("first", "thread"),
            ClientOrchestrationCommand.UnarchiveThread("second", "thread"),
        )

        try {
            dispatcher.dispatchInOrder(commands)
            error("Expected dispatch failure")
        } catch (_: IllegalStateException) {
            // Both commands were persisted before the first network attempt.
        }

        val queued = outbox.commands(environment.id)
        assertEquals(listOf("first", "second"), queued.map { it.command.commandId })
        assertEquals(listOf(OutboxCommandStatus.FAILED, OutboxCommandStatus.PENDING), queued.map { it.status })
        driver.close()
    }

    @Test
    fun `failed command survives recreation and replay keeps its command id`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val io = StandardTestDispatcher(testScheduler)
        val environment = savedEnvironment()
        val client = RecoveringClient()
        val firstOutbox = SqlCommandOutbox(database, io)
        val dispatcher = DurableCommandDispatcher(
            environments = OutboxEnvironmentRepository(environment),
            credentials = OutboxCredentialStore,
            client = client,
            outbox = firstOutbox,
        )
        val command = ClientOrchestrationCommand.SetRuntimeMode(
            commandId = "stable-command-id",
            threadId = "thread",
            runtimeMode = "provider-defined-mode",
            createdAt = "2026-08-06T00:00:00Z",
        )

        val failure = try {
            dispatcher.dispatch(command)
            error("Expected dispatch failure")
        } catch (cause: IllegalStateException) {
            cause
        }
        assertEquals("offline", failure.message)
        val failed = firstOutbox.observe(environment.id).first().single()
        assertEquals(OutboxCommandStatus.FAILED, failed.status)
        assertEquals(1, failed.attemptCount)
        assertEquals("offline", failed.lastError)
        assertEquals("provider-defined-mode", (failed.command as ClientOrchestrationCommand.SetRuntimeMode).runtimeMode)

        val recreated = SqlCommandOutbox(database, io)
        val replay = DurableCommandDispatcher(
            environments = OutboxEnvironmentRepository(environment),
            credentials = OutboxCredentialStore,
            client = client.apply { failing = false },
            outbox = recreated,
        )
        replay.replay(environment)

        assertEquals(listOf("stable-command-id", "stable-command-id"), client.commandIds)
        assertTrue(recreated.commands(environment.id).isEmpty())
        driver.close()
    }
}

private class RecoveringClient : OrchestrationCommandClient {
    var failing = true
    val commandIds = mutableListOf<String>()

    override suspend fun dispatch(
        baseUrl: String,
        bearerToken: String,
        command: ClientOrchestrationCommand,
    ): DispatchResult {
        commandIds += command.commandId
        if (failing) error("offline")
        return DispatchResult(1)
    }
}

private object OutboxCredentialStore : CredentialStore {
    override suspend fun read(environmentId: String) = "token"
    override suspend fun write(environmentId: String, bearerToken: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
}

private class OutboxEnvironmentRepository(environment: SavedEnvironment) : EnvironmentRepository {
    override val environments: StateFlow<List<SavedEnvironment>> = MutableStateFlow(listOf(environment))
    override val activeEnvironment: StateFlow<SavedEnvironment?> = MutableStateFlow(environment)
    override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) = Unit
    override suspend fun select(environmentId: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
    override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
}

private fun savedEnvironment() = SavedEnvironment(
    id = "environment",
    label = "Environment",
    baseUrl = "https://example.test/",
    platformOs = "linux",
    platformArch = "x64",
    serverVersion = "1",
    active = true,
    lastConnectedAt = null,
)
