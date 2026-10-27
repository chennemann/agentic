package de.chennemann.agentic.t3.contract

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadSearchContractsTest {
    @Test
    fun `search results decode the server encoding and tolerate omitted additive fields`() {
        val result = T3Json.decodeFromString(
            OrchestrationSearchThreadsResult.serializer(),
            """{"matches":[
                {"hits":3,"threadId":"thread-1","messageId":"message-9","projectId":"project-1","source":"assistant",
                 "snippet":"…the indexed search ranks titles first…","messageCreatedAt":"2026-10-24T10:00:00.000Z","quality":1,"future":true},
                {"threadId":"thread-2","projectId":"project-1","source":"user","snippet":"search please","messageCreatedAt":null}
            ],"snapshotSequence":42}"""
        )
        assertEquals(42L, result.snapshotSequence)
        assertEquals(listOf("thread-1", "thread-2"), result.matches.map { it.threadId })
        assertEquals("message-9", result.matches[0].messageId)
        assertEquals(1.0, result.matches[0].quality)
        assertEquals(3, result.matches[0].hits)
        assertNull(result.matches[1].messageId)
        assertNull(result.matches[1].messageCreatedAt)
    }

    @Test
    fun `search input trims the query to the server limit and bounds the limit`() {
        val input = orchestrationSearchThreadsInput("  ${"a".repeat(250)}  ", 500)
        assertEquals(THREAD_SEARCH_QUERY_MAX_LENGTH, input.getValue("query").jsonPrimitive.content.length)
        assertEquals(THREAD_SEARCH_MAX_LIMIT, input.getValue("limit").jsonPrimitive.int)
    }

    @Test
    fun `shell threads keep run activity and pending background work`() {
        val thread = V2Orchestration.shellThread(
            T3Json.parseToJsonElement(
                """{"id":"thread-1","projectId":"project-1","title":"Conversation","providerInstanceId":"custom",
                "runtimeMode":"full-access","interactionMode":"default","latestRunId":"run-1","activeRunId":"run-1",
                "status":"completed","activityRunStatus":"running","activityRunStartedAt":"2026-10-24T10:00:00.000Z",
                "blockedOnThreadWait":true,"pendingRuntimeRequest":null,
                "pendingBackgroundTasks":[{"taskId":"t1","kind":"command"},{"taskId":"t2","kind":"monitor","awaited":true},{"taskId":"t3","kind":"future_kind"}],
                "createdAt":"2026-10-24T09:00:00.000Z","updatedAt":"2026-10-24T10:00:00.000Z"}"""
            ) as kotlinx.serialization.json.JsonObject
        )
        assertEquals("completed", thread.runStatus)
        assertEquals("running", thread.activityRunStatus)
        assertEquals("2026-10-24T10:00:00.000Z", thread.activityRunStartedAt)
        assertTrue(thread.blockedOnThreadWait)
        assertEquals(listOf("command", "monitor", "future_kind"), thread.pendingBackgroundTasks.map { it.kind })
        assertTrue(thread.pendingBackgroundTasks[1].awaited)
        val restored = T3Json.decodeFromString(
            OrchestrationThreadShell.serializer(),
            T3Json.encodeToString(OrchestrationThreadShell.serializer(), thread)
        )
        assertEquals(thread, restored)
    }

    @Test
    fun `only awaited commands and monitors hold completion while other kinds always do`() {
        assertFalse(
            listOf(
                OrchestrationPendingBackgroundTask("dev", "command"),
                OrchestrationPendingBackgroundTask("watch", "monitor")
            ).holdsCompletion()
        )
        assertTrue(listOf(OrchestrationPendingBackgroundTask("wait", "command", awaited = true)).holdsCompletion())
        assertTrue(listOf(OrchestrationPendingBackgroundTask("agent", "subagent")).holdsCompletion())
        assertTrue(listOf(OrchestrationPendingBackgroundTask("other", "future_kind")).holdsCompletion())
        assertFalse(emptyList<OrchestrationPendingBackgroundTask>().holdsCompletion())
    }
}
