package de.chennemann.agentic.t3.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrchestrationV2Test {
    @Test
    fun `v1 snapshots and event envelopes are rejected`() {
        assertThrows(NoSuchElementException::class.java) {
            V2Orchestration.threadItem(json("""{"kind":"snapshot","snapshot":{"snapshotSequence":1,"thread":{}}}"""))
        }
        assertThrows(NoSuchElementException::class.java) {
            V2Orchestration.threadItem(
                json("""{"kind":"event","event":{"sequence":1,"type":"thread.message-sent","payload":{}}}""")
            )
        }
    }

    @Test
    fun `shell reads active and archived threads and maps run status`() {
        val item = V2Orchestration.shellItem(
            json(
                """{"kind":"snapshot","snapshot":{
            "schemaVersion":2,"snapshotSequence":42,"projects":[],"threads":[$thread],
            "archivedThreads":[${thread.replace("thread-1", "archived-1")}]
        }}"""
            )
        ) as OrchestrationShellStreamItem.Snapshot
        assertEquals(2, item.snapshot.threads.size)
        assertEquals("running", item.snapshot.threads.first().session?.status)
        assertEquals("run-1", item.snapshot.threads.first().latestTurn?.turnId)
        val update = V2Orchestration.shellItem(
            json("""{"kind":"thread.updated","sequence":43,"location":"active","thread":$thread}""")
        )
        assertTrue(update is OrchestrationShellStreamItem.ThreadUpserted)
        assertTrue(
            V2Orchestration.shellItem(
                json("""{"kind":"todo-removed","sequence":44,"todoId":"todo-1"}""")
            ) is OrchestrationShellStreamItem.Ignored
        )
    }

    @Test
    fun `snapshot and events preserve visible history reasoning and unknown activities`() {
        val projection = projection()
        val snapshot = V2Orchestration.threadItem(
            json(
                """{"kind":"snapshot","snapshotSequence":42,
            "projection":$projection,"historyCursor":"opaque","hasMoreHistory":true}"""
            )
        ) as OrchestrationThreadStreamItem.Snapshot
        assertEquals("Hello", snapshot.snapshot.thread.messages.single().text)
        assertTrue(snapshot.snapshot.page!!.hasMore)
        assertEquals("running", snapshot.snapshot.thread.session?.status)
        var state = snapshot.snapshot.projection
        val reasoning = item("reasoning", "reason-1", "\"text\":\"Thinking\",\"streaming\":true")
        state = state.apply(V2EventPayload("turn-item.updated", json(reasoning)))
        assertEquals("reasoning", state.detail().messages.last().role)
        state =
            state.apply(
                V2EventPayload(
                    "turn-item.updated",
                    json(item("future_provider_activity", "future-1", "\"summary\":\"new activity\""))
                )
            )
        assertEquals("future_provider_activity", state.detail().activities.single().kind)
        val completed =
            json(
                item(
                    "assistant_message",
                    "message-item",
                    "\"messageId\":\"message-1\",\"text\":\"Hello world\",\"streaming\":false"
                )
            )
        state = state.apply(V2EventPayload("turn-item.updated", completed))
        assertEquals("Hello world", state.detail().messages.first().text)
        assertFalse(state.detail().messages.first().streaming)
    }

    @Test
    fun `runtime request updates close approval cards`() {
        val approval =
            json(item("approval_request", "approval-1", "\"requestId\":\"request-1\",\"requestKind\":\"command\""))
        val request =
            json(
                """{"id":"request-1","kind":"command","status":"pending","responseCapability":{"type":"live","providerSessionId":"session-1"}}"""
            )
        val projection = T3Json.decodeFromString<V2Projection>(projection())
            .apply(V2EventPayload("turn-item.updated", approval))
            .apply(V2EventPayload("runtime-request.updated", request))
        assertEquals("approval.requested", projection.detail().activities.single().kind)
        val resolved = json(request.toString().replace("pending", "resolved"))
        assertEquals(
            "approval.resolved",
            projection.apply(V2EventPayload("runtime-request.updated", resolved)).detail().activities.single().kind
        )
    }

    @Test
    fun `dispatch uses v2 message and runtime request commands`() {
        val start = start()
        val requests = V2Commands.requests(start, V2Commands.message(start, JsonArray(emptyList()), emptyMap()))
        val request = requests.last()
        assertEquals("message.dispatch", request.payload.getValue("type").jsonPrimitive.content)
        assertEquals("user", request.payload["createdBy"]?.jsonPrimitive?.content)
        assertEquals("mobile", request.payload["creationSource"]?.jsonPrimitive?.content)
        assertEquals("message-1", request.payload.getValue("messageId").jsonPrimitive.content)
        assertFalse(request.payload.containsKey("message"))
        assertEquals("auto", request.payload.getValue("deliveryIntent").jsonPrimitive.content)
        val response = V2Commands.requests(
            ClientOrchestrationCommand.RespondToApproval("command", "thread-1", "request-1", "accept")
        ).single()
        assertEquals("runtime-request.respond", response.payload.getValue("type").jsonPrimitive.content)
        val stop = V2Commands.requests(
            ClientOrchestrationCommand.InterruptTurn("command", "thread-1", "run-1")
        ).single()
        assertEquals("run.interrupt", stop.payload.getValue("type").jsonPrimitive.content)
        assertEquals("run-1", stop.payload.getValue("runId").jsonPrimitive.content)
    }

    @Test
    fun `new threads launch with workspace preparation and uploaded attachment references`() {
        val start = start().copy(
            bootstrap = StartTurnBootstrap(
                NewThreadBootstrap("project-1", "Title"),
                PrepareWorktreeBootstrap("main", startFromOrigin = true)
            )
        )
        val request = V2Commands.requests(start, V2Commands.message(start, JsonArray(emptyList()), emptyMap())).single()
        assertEquals("orchestration.launchThread", request.method)
        assertEquals(
            "main",
            request.payload.getValue("workspaceStrategy").jsonObject.getValue("baseRef").jsonPrimitive.content
        )
        assertEquals(
            "worktree",
            request.payload.getValue("workspaceStrategy").jsonObject.getValue("type").jsonPrimitive.content
        )
        assertEquals(
            "custom-provider",
            request.payload.getValue("modelSelection").jsonObject.getValue("instanceId").jsonPrimitive.content
        )
    }

    private fun start() = ClientOrchestrationCommand.StartTurn(
        "command",
        "thread-1",
        TurnMessageInput("message-1", text = "Hello"),
        ModelSelection("custom-provider", "custom-model"),
        interactionMode = "default",
        runtimeMode = "full-access"

    )

    private fun projection() =
        """{"thread":$thread,"runs":[{"id":"run-1","ordinal":1,"status":"running","requestedAt":"2026-10-19T10:00:00Z"}],
        "visibleTurnItems":[{"position":0,"visibility":"local","sourceThreadId":"thread-1","sourceItemId":"message-item",
        "item":${item(
            "assistant_message",
            "message-item",
            "\"messageId\":\"message-1\",\"text\":\"Hello\",\"streaming\":true"
        )}}],
        "updatedAt":"2026-10-19T10:00:00Z"}"""

    private fun item(type: String, id: String, fields: String) =
        """{"id":"$id","threadId":"thread-1","runId":"run-1","type":"$type",
        "ordinal":1,"status":"running","updatedAt":"2026-10-19T10:00:00Z",$fields}"""

    private fun json(value: String): JsonObject = T3Json.parseToJsonElement(value).jsonObject

    private val thread = """{"id":"thread-1","projectId":"project-1","title":"Conversation","modelSelection":{"instanceId":"custom-provider","model":"custom-model"},
        "providerInstanceId":"custom-provider","runtimeMode":"full-access","interactionMode":"default","latestRunId":"run-1","activeRunId":"run-1","status":"running",
        "createdAt":"2026-10-19T10:00:00Z","updatedAt":"2026-10-19T10:00:00Z"}"""
}
