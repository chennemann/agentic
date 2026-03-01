package de.chennemann.agentic.domain.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionEventReducerTest {
    private val reducer = SessionEventReducer()

    @Test
    fun ignoresHeartbeatEvents() {
        val event = SessionStreamEvent(
            directory = "repo",
            type = "server.heartbeat",
            properties = JsonObject(emptyMap()),
            id = null,
            retry = null,
        )

        val action = reducer.reduce(event)

        assertTrue(action is SessionEventAction.Ignore)
    }

    @Test
    fun parsesMessageUpdatedEvent() {
        val event = SessionStreamEvent(
            directory = "repo",
            type = "message.updated",
            properties = buildJsonObject {
                put("info", buildJsonObject {
                    put("sessionID", "s1")
                    put("id", "m1")
                    put("role", "assistant")
                    put("text", "hi")
                    put("time", buildJsonObject {
                        put("created", 100)
                        put("completed", 200)
                    })
                })
            },
            id = null,
            retry = null,
        )

        val action = reducer.reduce(event)

        assertTrue(action is SessionEventAction.MessageUpdated)
        val value = action as SessionEventAction.MessageUpdated
        assertEquals("s1", value.sessionId)
        assertEquals("m1", value.messageId)
        assertEquals("assistant", value.role)
        assertEquals("hi", value.text)
        assertEquals(100L, value.createdAt)
        assertEquals(200L, value.completedAt)
    }

    @Test
    fun dropsMessageUpdatedWithoutSessionId() {
        val event = SessionStreamEvent(
            directory = "repo",
            type = "message.updated",
            properties = JsonObject(emptyMap()),
            id = null,
            retry = null,
        )

        val action = reducer.reduce(event)

        assertTrue(action is SessionEventAction.Drop)
        assertEquals("missing sessionID", (action as SessionEventAction.Drop).reason)
    }

    @Test
    fun parsesSessionDeletedEvent() {
        val event = SessionStreamEvent(
            directory = "repo",
            type = "session.deleted",
            properties = buildJsonObject {
                put("info", buildJsonObject {
                    put("id", "s1")
                })
            },
            id = null,
            retry = null,
        )

        val action = reducer.reduce(event)

        assertTrue(action is SessionEventAction.SessionChanged)
        val value = action as SessionEventAction.SessionChanged
        assertEquals("session.deleted", value.type)
        assertEquals("repo", value.directory)
        assertEquals("s1", value.deletedSessionId)
    }

    @Test
    fun dropsPartRemovedWithoutPartId() {
        val event = SessionStreamEvent(
            directory = "repo",
            type = "message.part.removed",
            properties = buildJsonObject {
                put("messageID", "m1")
            },
            id = null,
            retry = null,
        )

        val action = reducer.reduce(event)

        assertTrue(action is SessionEventAction.Drop)
        assertEquals("missing partID", (action as SessionEventAction.Drop).reason)
    }

    @Test
    fun parsesSessionDiffEvent() {
        val event = SessionStreamEvent(
            directory = "repo",
            type = "session.diff",
            properties = buildJsonObject {
                put("sessionID", "s1")
            },
            id = null,
            retry = null,
        )

        val action = reducer.reduce(event)

        assertTrue(action is SessionEventAction.SessionDiff)
        val value = action as SessionEventAction.SessionDiff
        assertEquals("s1", value.sessionId)
        assertEquals("repo", value.directory)
    }

    @Test
    fun parsesSessionStatusEvent() {
        val event = SessionStreamEvent(
            directory = "repo",
            type = "session.status",
            properties = buildJsonObject {
                put("sessionID", "s1")
                put("status", buildJsonObject {
                    put("type", "busy")
                })
            },
            id = null,
            retry = null,
        )

        val action = reducer.reduce(event)

        assertTrue(action is SessionEventAction.SessionStatus)
        val value = action as SessionEventAction.SessionStatus
        assertEquals("s1", value.sessionId)
        assertEquals("repo", value.directory)
        assertEquals("busy", value.status)
    }

    @Test
    fun parsesSessionIdleEvent() {
        val event = SessionStreamEvent(
            directory = "repo",
            type = "session.idle",
            properties = buildJsonObject {
                put("sessionID", "s1")
            },
            id = null,
            retry = null,
        )

        val action = reducer.reduce(event)

        assertTrue(action is SessionEventAction.SessionStatus)
        val value = action as SessionEventAction.SessionStatus
        assertEquals("s1", value.sessionId)
        assertEquals("idle", value.status)
    }
}
