package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.T3CommandJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandServicesTest {
    private val environment = SavedEnvironment(
        id = "environment",
        label = "Environment",
        baseUrl = "https://example.test/",
        platformOs = "linux",
        platformArch = "x64",
        serverVersion = "1",
        active = true,
        lastConnectedAt = null,
    )
    private val environments = FakeEnvironmentRepository(environment)
    private val commands = RecordingCommandDispatcher()
    private val service = ChatService(commands)

    @Test
    fun `new turn creates the thread before starting it with unique client ids`() = runTest {
        service.startTurn(
            threadId = null,
            projectId = "project",
            prompt = "\n First useful line\nSecond",
            modelSelection = ModelSelection("instance", "model"),
            runtimeMode = "approval-required",
        )
        val firstCreate = commands.recorded[0] as ClientOrchestrationCommand.CreateThread
        val firstStart = commands.recorded[1] as ClientOrchestrationCommand.StartTurn
        commands.recorded.clear()
        service.startTurn(
            threadId = null,
            projectId = "project",
            prompt = "Another",
            modelSelection = ModelSelection("instance", "model"),
            runtimeMode = "full-access",
        )
        val secondCreate = commands.recorded[0] as ClientOrchestrationCommand.CreateThread
        val secondStart = commands.recorded[1] as ClientOrchestrationCommand.StartTurn

        assertEquals("First useful line Second", firstCreate.title)
        val encoded = T3CommandJson
            .encodeToJsonElement(ClientOrchestrationCommand.serializer(), firstStart)
            .jsonObject
        assertNull(encoded["titleSeed"])
        assertTrue("bootstrap" !in encoded)
        assertEquals("project", firstCreate.projectId)
        assertEquals("default", firstCreate.interactionMode)
        assertEquals("default", firstStart.interactionMode)
        assertNull(firstCreate.branch)
        assertNull(firstCreate.worktreePath)
        assertEquals(firstCreate.threadId, firstStart.threadId)
        assertNotEquals(firstCreate.commandId, firstStart.commandId)
        assertNotEquals(firstCreate.commandId, secondCreate.commandId)
        assertNotEquals(firstCreate.threadId, secondCreate.threadId)
        assertNotEquals(firstStart.message.messageId, secondStart.message.messageId)
    }

    @Test
    fun `existing turn starts without creating a thread`() = runTest {
        service.startTurn(
            threadId = "existing-thread",
            projectId = "project",
            prompt = "Continue",
            modelSelection = ModelSelection("instance", "model"),
            runtimeMode = "full-access",
        )

        val start = commands.recorded.single() as ClientOrchestrationCommand.StartTurn
        assertEquals("existing-thread", start.threadId)
        assertEquals("default", start.interactionMode)
    }

    @Test
    fun `approval and structured user input map to RPC commands`() = runTest {
        service.respondToApproval("thread", "approval", "acceptForSession")
        service.respondToUserInput(
            "thread",
            "input",
            JsonObject(mapOf("target" to JsonPrimitive("debug"))),
        )

        val approval = commands.recorded[0] as ClientOrchestrationCommand.RespondToApproval
        val input = commands.recorded[1] as ClientOrchestrationCommand.RespondToUserInput
        assertEquals("acceptForSession", approval.decision)
        assertEquals("debug", input.answers.getValue("target").toString().trim('"'))
    }

    @Test
    fun `accepted plan continuation maps exact thread and plan through start turn`() = runTest {
        service.continuePlan(
            threadId = "thread-plan",
            planId = "plan-provider-neutral",
            modelSelection = ModelSelection("instance", "model"),
            runtimeMode = "approval-required",
        )

        val command = commands.recorded.single() as ClientOrchestrationCommand.StartTurn
        assertEquals("thread-plan", command.threadId)
        assertEquals("thread-plan", command.sourceProposedPlan?.threadId)
        assertEquals("plan-provider-neutral", command.sourceProposedPlan?.planId)
        assertEquals("default", command.interactionMode)
        assertEquals("Implement the accepted plan.", command.message.text)
    }

    @Test
    fun `session stop and turn interrupt remain distinct RPC commands`() = runTest {
        service.interrupt("thread-1", "turn-1")
        service.terminateSession("thread-1")

        val interrupt = commands.recorded[0] as ClientOrchestrationCommand.InterruptTurn
        val terminate = commands.recorded[1] as ClientOrchestrationCommand.StopSession
        assertEquals("thread-1", interrupt.threadId)
        assertEquals("turn-1", interrupt.turnId)
        assertEquals("thread-1", terminate.threadId)
        assertNotEquals(interrupt.commandId, terminate.commandId)
    }

    @Test
    fun `thread service maps snooze and explicit wake without archive semantics`() = runTest {
        val threadService = ThreadService(environments, EmptyOrchestrationRepository(), commands)

        threadService.snooze("thread-1", "2026-08-07T09:00:00Z")
        threadService.wake("thread-1")

        val snooze = commands.recorded[0] as ClientOrchestrationCommand.SnoozeThread
        val wake = commands.recorded[1] as ClientOrchestrationCommand.UnsnoozeThread
        assertEquals("thread-1", snooze.threadId)
        assertEquals("2026-08-07T09:00:00Z", snooze.snoozedUntil)
        assertEquals("thread-1", wake.threadId)
        assertEquals("user", wake.reason)
        assertTrue(commands.recorded.none { it is ClientOrchestrationCommand.ArchiveThread })
    }

    @Test
    fun `thread service maps permanent deletion to its own durable command`() = runTest {
        val threadService = ThreadService(environments, EmptyOrchestrationRepository(), commands)

        threadService.delete("thread-1")

        val deletion = commands.recorded.single() as ClientOrchestrationCommand.DeleteThread
        assertEquals("thread-1", deletion.threadId)
        assertTrue(commands.recorded.none { it is ClientOrchestrationCommand.ArchiveThread })
    }
}

private class RecordingCommandDispatcher : CommandDispatcher {
    val recorded = mutableListOf<ClientOrchestrationCommand>()

    override suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult {
        recorded += command
        return DispatchResult(recorded.size.toLong())
    }
}

private class FakeEnvironmentRepository(
    environment: SavedEnvironment,
) : EnvironmentRepository {
    override val environments: StateFlow<List<SavedEnvironment>> = MutableStateFlow(listOf(environment))
    override val activeEnvironment: StateFlow<SavedEnvironment?> = MutableStateFlow(environment)

    override suspend fun save(
        baseUrl: String,
        descriptor: ExecutionEnvironmentDescriptor,
        makeActive: Boolean,
    ) = Unit

    override suspend fun select(environmentId: String) = Unit

    override suspend fun remove(environmentId: String) = Unit

    override suspend fun markConnected(
        environmentId: String,
        connectedAt: Long,
    ) = Unit
}

private class EmptyOrchestrationRepository : OrchestrationRepository {
    override val clientConfig = MutableStateFlow(ProjectionState<ServerConfig>())
    override val shell = MutableStateFlow(ProjectionState<OrchestrationShellSnapshot>())
    override val focusedThread = MutableStateFlow(ProjectionState<OrchestrationThreadDetailSnapshot>())
    override val focusedThreadId = MutableStateFlow<String?>(null)
    override val selectedProjectId = MutableStateFlow<String?>(null)
    override suspend fun loadCached(environmentId: String) = Unit
    override suspend fun setClientConfig(environmentId: String, config: ServerConfig, source: ProjectionSource) = Unit
    override suspend fun setShellSnapshot(environmentId: String, snapshot: OrchestrationShellSnapshot, source: ProjectionSource) = Unit
    override suspend fun applyShellItem(environmentId: String, item: OrchestrationShellStreamItem) = Reduction.Ignored(shell.value)
    override suspend fun focusThread(environmentId: String, threadId: String?) = Unit
    override suspend fun selectProject(environmentId: String, projectId: String?) = Unit
    override suspend fun setThreadSnapshot(
        environmentId: String,
        snapshot: OrchestrationThreadDetailSnapshot,
        source: ProjectionSource,
    ) = Unit
    override suspend fun applyThreadItem(environmentId: String, threadId: String, item: OrchestrationThreadStreamItem) =
        Reduction.Ignored(focusedThread.value)
    override suspend fun clearEnvironment(environmentId: String) = Unit
}
