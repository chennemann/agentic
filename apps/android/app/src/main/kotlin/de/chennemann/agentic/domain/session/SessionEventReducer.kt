package de.chennemann.agentic.domain.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface SessionEventAction {
    data class Ignore(val type: String) : SessionEventAction

    data object ReloadProjects : SessionEventAction

    data class SessionChanged(val type: String, val directory: String, val deletedSessionId: String?) : SessionEventAction

    data class MessageUpdated(
        val sessionId: String,
        val messageId: String,
        val role: String,
        val text: String?,
        val createdAt: Long?,
        val completedAt: Long?,
        val directory: String,
    ) : SessionEventAction

    data class MessageRemoved(
        val sessionId: String,
        val messageId: String,
        val directory: String,
    ) : SessionEventAction

    data class MessagePartUpdated(
        val sessionId: String,
        val messageId: String,
        val part: JsonObject,
        val directory: String,
    ) : SessionEventAction

    data class MessagePartRemoved(
        val messageId: String,
        val partId: String,
    ) : SessionEventAction

    data class SessionStatus(
        val sessionId: String,
        val directory: String,
        val status: String,
    ) : SessionEventAction

    data class SessionDiff(
        val sessionId: String,
        val directory: String,
    ) : SessionEventAction

    data class Drop(val type: String, val reason: String) : SessionEventAction
}

class SessionEventReducer {
    fun reduce(event: SessionStreamEvent): SessionEventAction {
        if (event.type == "server.connected" || event.type == "server.heartbeat") {
            return SessionEventAction.Ignore(event.type)
        }
        if (event.type == "server.instance.disposed" || event.type == "global.disposed") {
            return SessionEventAction.ReloadProjects
        }
        if (event.type == "session.created" || event.type == "session.updated" || event.type == "session.deleted") {
            val deleted = if (event.type == "session.deleted") {
                event.properties["info"]
                    ?.jsonObject
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.contentOrNull
            } else {
                null
            }
            return SessionEventAction.SessionChanged(event.type, event.directory, deleted)
        }
        if (event.type == "message.updated") {
            val info = event.properties["info"]?.jsonObject ?: event.properties
            val sessionId = info["sessionID"]?.jsonPrimitive?.contentOrNull
            if (sessionId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing sessionID")
            val messageId = info["id"]?.jsonPrimitive?.contentOrNull
            if (messageId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing message id")
            return SessionEventAction.MessageUpdated(
                sessionId = sessionId,
                messageId = messageId,
                role = info["role"]?.jsonPrimitive?.contentOrNull ?: "assistant",
                text = info["text"]?.jsonPrimitive?.contentOrNull?.trim(),
                createdAt = (info["time"] as? JsonObject)
                    ?.get("created")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toLongOrNull(),
                completedAt = (info["time"] as? JsonObject)
                    ?.get("completed")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toLongOrNull(),
                directory = event.directory,
            )
        }
        if (event.type == "message.removed") {
            val sessionId = event.properties["sessionID"]?.jsonPrimitive?.contentOrNull
            if (sessionId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing sessionID")
            val messageId = event.properties["messageID"]?.jsonPrimitive?.contentOrNull
            if (messageId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing messageID")
            return SessionEventAction.MessageRemoved(
                sessionId = sessionId,
                messageId = messageId,
                directory = event.directory,
            )
        }
        if (event.type == "message.part.updated") {
            val part = event.properties["part"]?.jsonObject ?: event.properties
            val sessionId = part["sessionID"]?.jsonPrimitive?.contentOrNull
            if (sessionId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing sessionID")
            val messageId = part["messageID"]?.jsonPrimitive?.contentOrNull
            if (messageId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing messageID")
            val partId = part["id"]?.jsonPrimitive?.contentOrNull
            if (partId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing part id")
            return SessionEventAction.MessagePartUpdated(
                sessionId = sessionId,
                messageId = messageId,
                part = part,
                directory = event.directory,
            )
        }
        if (event.type == "message.part.removed") {
            val messageId = event.properties["messageID"]?.jsonPrimitive?.contentOrNull
            if (messageId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing messageID")
            val partId = event.properties["partID"]?.jsonPrimitive?.contentOrNull
            if (partId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing partID")
            return SessionEventAction.MessagePartRemoved(
                messageId = messageId,
                partId = partId,
            )
        }
        if (event.type == "session.status") {
            val sessionId = event.properties["sessionID"]?.jsonPrimitive?.contentOrNull
            if (sessionId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing sessionID")
            val status = event.properties["status"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: "busy"
            return SessionEventAction.SessionStatus(sessionId, event.directory, status)
        }
        if (event.type == "session.idle") {
            val sessionId = event.properties["sessionID"]?.jsonPrimitive?.contentOrNull
            if (sessionId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing sessionID")
            return SessionEventAction.SessionStatus(sessionId, event.directory, "idle")
        }
        if (event.type == "session.diff") {
            val sessionId = event.properties["sessionID"]?.jsonPrimitive?.contentOrNull
            if (sessionId.isNullOrBlank()) return SessionEventAction.Drop(event.type, "missing sessionID")
            return SessionEventAction.SessionDiff(sessionId, event.directory)
        }
        return SessionEventAction.Drop(event.type, "unhandled event")
    }
}
