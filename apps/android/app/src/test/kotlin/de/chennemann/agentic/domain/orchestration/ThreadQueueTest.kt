package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.V2Projection
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadQueueTest {
    @Test
    fun `queued user messages follow queue position and skip automatic completions`() {
        val state = projection(
            runs = listOf(
                run("active", "running", 1, attempt = "attempt-1"),
                run("second", "queued", 3, position = 2),
                run("first", "queued", 4, position = 1),
                run("wake", "queued", 2),
                run("done", "completed", 0),
            ),
            messages = listOf(
                message("second", "Second"),
                message("first", "First", attachments = 2),
                """{"id":"message-wake","role":"user","text":"Task finished","attachments":[],
                    "notification":{"kind":"delegated"},"createdAt":"2026-10-27T10:00:00Z"}""",
            ),
            sessions = listOf(session(steering = true)),
        ).queueState()

        assertEquals(listOf("first", "second"), state.queuedRuns.map { it.runId })
        assertEquals(listOf("First", "Second"), state.queuedRuns.map { it.text })
        assertEquals(2, state.queuedRuns.first().attachmentCount)
        assertEquals("active", state.activeRunId)
        assertTrue(state.canPromoteToSteer)
        assertFalse(state.canSendNow)
        assertFalse(state.held)
    }

    @Test
    fun `a stopped thread holds its queue and sends a selected message now`() {
        val state = projection(
            runs = listOf(run("stopped", "interrupted", 1), run("held", "queued", 2, held = true)),
            messages = listOf(message("held", "Later")),
        ).queueState()

        assertTrue(state.held)
        assertTrue(state.canSendNow)
        assertFalse(state.canPromoteToSteer)
        assertNull(state.activeRunId)
    }

    @Test
    fun `steering needs a running attempt and a provider that steers`() {
        val waiting = projection(runs = listOf(run("active", "waiting", 1, attempt = "attempt-1"))).queueState()
        assertFalse(waiting.canPromoteToSteer)
        val preparing = projection(runs = listOf(run("active", "preparing", 1))).queueState()
        assertEquals("preparing", preparing.activeRunStatus)
        assertFalse(preparing.canPromoteToSteer)
        val queueOnlyProvider = projection(
            runs = listOf(run("active", "running", 1, attempt = "attempt-1")),
            sessions = listOf(session(steering = false)),
        ).queueState()
        assertFalse(queueOnlyProvider.canPromoteToSteer)
    }

    @Test
    fun `guest messages show the guest's words and are not editable`() {
        val state = projection(
            runs = listOf(run("guest", "queued", 1)),
            messages = listOf(
                """{"id":"message-guest","role":"user","text":"Ana says: hi","attachments":[],
                    "context":{"guest":{"name":"Ana","text":"hi"}},"createdAt":"2026-10-27T10:00:00Z"}""",
            ),
        ).queueState()

        assertEquals("hi", state.queuedRuns.single().text)
        assertTrue(state.queuedRuns.single().guest)
    }

    @Test
    fun `queued follow ups travel durably while queue management goes out live`() = runTest {
        val durable = Recording()
        val live = Recording()
        val service = ThreadQueueService(durable, live)

        service.enqueue("thread", "Later", ModelSelection("provider", "model"), "full-access")
        service.promoteToSteer("thread", "queued", "active")
        service.sendNow("thread", "queued")
        service.resume("thread")
        service.cancel("thread", "queued")
        service.edit("thread", "queued", "  Edited  ")

        assertTrue((durable.commands.single() as ClientOrchestrationCommand.StartTurn).queue)
        assertEquals(
            listOf(
                ClientOrchestrationCommand.PromoteQueuedRun::class,
                ClientOrchestrationCommand.StartQueuedRun::class,
                ClientOrchestrationCommand.ResumeQueue::class,
                ClientOrchestrationCommand.CancelQueuedRun::class,
                ClientOrchestrationCommand.EditQueuedRun::class,
            ),
            live.commands.map { it::class },
        )
        assertEquals("Edited", (live.commands.last() as ClientOrchestrationCommand.EditQueuedRun).text)
        assertTrue(runCatching { service.edit("thread", "queued", "   ") }.exceptionOrNull() is IllegalArgumentException)
    }

    private class Recording : CommandDispatcher {
        val commands = mutableListOf<ClientOrchestrationCommand>()
        override suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult {
            commands += command
            return DispatchResult(1)
        }
    }

    private fun projection(
        runs: List<String>,
        messages: List<String> = emptyList(),
        sessions: List<String> = emptyList(),
    ): V2Projection = T3Json.decodeFromString(
        V2Projection.serializer(),
        """
        {
          "thread": {"id":"thread-1","projectId":"project-1","title":"Thread","archivedAt":null,"deletedAt":null,
            "createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:00:00Z"},
          "runs": [${runs.joinToString(",")}],
          "messages": [${messages.joinToString(",")}],
          "providerSessions": [${sessions.joinToString(",")}],
          "updatedAt": "2026-10-27T10:00:00Z"
        }
        """.trimIndent(),
    )

    private fun run(
        id: String,
        status: String,
        ordinal: Int,
        attempt: String? = null,
        position: Int? = null,
        held: Boolean = false,
    ) = """
        {"id":"$id","threadId":"thread-1","ordinal":$ordinal,"providerInstanceId":"provider",
         "modelSelection":{"instanceId":"provider","model":"model"},"providerThreadId":null,
         "userMessageId":"message-$id","rootNodeId":null,"activeAttemptId":${attempt?.let { "\"$it\"" } ?: "null"},
         "status":"$status"${position?.let { ",\"queuePosition\":$it" } ?: ""}${if (held) ",\"queueHeld\":true" else ""},
         "requestedAt":"2026-10-27T10:00:00Z","startedAt":null,"completedAt":null,
         "checkpointId":null,"contextHandoffId":null}
    """.trimIndent()

    private fun message(runId: String, text: String, attachments: Int = 0) =
        """{"id":"message-$runId","role":"user","text":"$text","attachments":[${
            (1..attachments).joinToString(",") {
                """{"type":"image","id":"a$it","name":"a$it.png","mimeType":"image/png","sizeBytes":1}"""
            }
        }],"createdAt":"2026-10-27T10:00:00Z"}"""

    private fun session(steering: Boolean) = """
        {"id":"session-1","driver":"codex","providerInstanceId":"provider","status":"running","cwd":"/workspace",
         "model":"model","createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:00:00Z","lastError":null,
         "capabilities":{"turns":{"exposesNativeTurnId":true,"emitsTurnStarted":true,"emitsTurnCompleted":true,
           "supportsInterrupt":true,"supportsActiveSteering":$steering,"supportsSteeringByInterruptRestart":false,
           "supportsQueuedMessages":true,"terminalStatusQuality":"strong"}}}
    """.trimIndent()
}
