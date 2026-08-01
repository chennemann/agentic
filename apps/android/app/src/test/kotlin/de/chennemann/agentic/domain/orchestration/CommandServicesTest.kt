package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.OrchestrationCommandClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.PortableCommandJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    private val credentials = FakeCredentialStore()
    private val commands = RecordingCommandClient()
    private val service = ChatService(environments, credentials, commands)

    @Test
    fun `new turn uses one atomic bootstrap command with unique client ids`() = runTest {
        service.startTurn(
            threadId = null,
            projectId = "project",
            prompt = "\n First useful line\nSecond",
            modelSelection = ModelSelection("instance", "model"),
            interactionMode = "plan",
            runtimeMode = "approval-required",
        )
        val first = commands.recorded.single() as ClientOrchestrationCommand.StartTurn
        commands.recorded.clear()
        service.startTurn(
            threadId = null,
            projectId = "project",
            prompt = "Another",
            modelSelection = ModelSelection("instance", "model"),
            interactionMode = "default",
            runtimeMode = "full-access",
        )
        val second = commands.recorded.single() as ClientOrchestrationCommand.StartTurn

        assertEquals("First useful line Second", first.bootstrap?.createThread?.title)
        val encoded = PortableCommandJson
            .encodeToJsonElement(ClientOrchestrationCommand.serializer(), first)
            .jsonObject
        assertNull(encoded["titleSeed"])
        assertEquals("project", first.bootstrap?.createThread?.projectId)
        assertNull(first.bootstrap?.createThread?.branch)
        assertNull(first.bootstrap?.createThread?.worktreePath)
        assertNotEquals(first.commandId, second.commandId)
        assertNotEquals(first.threadId, second.threadId)
        assertNotEquals(first.message.messageId, second.message.messageId)
    }

    @Test
    fun `approval and structured user input map to portable commands`() = runTest {
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
}

private class RecordingCommandClient : OrchestrationCommandClient {
    val recorded = mutableListOf<ClientOrchestrationCommand>()

    override suspend fun dispatch(
        baseUrl: String,
        bearerToken: String,
        command: ClientOrchestrationCommand,
    ): DispatchResult {
        recorded += command
        return DispatchResult(recorded.size.toLong())
    }
}

private class FakeCredentialStore : CredentialStore {
    override suspend fun read(environmentId: String): String = "token"

    override suspend fun write(
        environmentId: String,
        bearerToken: String,
    ) = Unit

    override suspend fun remove(environmentId: String) = Unit
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
