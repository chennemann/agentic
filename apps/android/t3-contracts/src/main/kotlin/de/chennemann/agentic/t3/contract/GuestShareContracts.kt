package de.chennemann.agentic.t3.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/** Public guest-link state the server keeps on the thread projection as `guestShare`. */
@Serializable
data class ThreadGuestShare(
    val id: String,
    val host: String,
    val createdAt: String,
    val expiresAt: String,
    val guestName: String? = null,
    val messages: List<ThreadGuestMessage> = emptyList()
)

@Serializable
data class ThreadGuestMessage(
    val id: String,
    val text: String = "",
    val status: String,
    val submittedAt: String,
    val attachments: List<ChatAttachment> = emptyList()
)

@Serializable
data class GuestShareCreateResult(val url: String, val share: ThreadGuestShare)

/** Guest-share RPC methods and commands of the current server contract. */
object GuestShareContracts {
    const val CREATE_METHOD = "guestShare.create"
    const val DISPATCH_METHOD = "orchestration.dispatchCommand"
    const val SETTINGS_METHOD = "server.getSettings"
    const val UPDATE_SETTINGS_METHOD = "server.updateSettings"
    const val SETTING_KEY = "guestSharingEnabled"

    /** Creating a link hands thread access to someone outside, so the server guards it like pairing. */
    const val CREATE_PERMISSION = "access:write"
    const val SETTINGS_PERMISSION = "settings:write"

    /** Absent, null, or an unreadable share all mean the thread is not shared. */
    fun share(thread: JsonObject): ThreadGuestShare? {
        val value = thread["guestShare"]?.takeUnless { it == JsonNull } ?: return null
        return runCatching { T3Json.decodeFromJsonElement<ThreadGuestShare>(value) }.getOrNull()
    }

    fun sharingEnabled(settings: JsonObject): Boolean = (settings[SETTING_KEY] as? JsonPrimitive)?.booleanOrNull == true

    fun createInput(threadId: String): JsonObject = buildJsonObject { put("threadId", threadId) }

    fun enableSettingsPatch(): JsonObject = buildJsonObject {
        put("patch", buildJsonObject { put(SETTING_KEY, true) })
    }

    fun close(commandId: String, threadId: String): JsonObject = buildJsonObject {
        put("type", "thread.guest-share.close")
        put("commandId", commandId)
        put("threadId", threadId)
    }

    fun dismiss(commandId: String, threadId: String, guestMessageId: String): JsonObject = buildJsonObject {
        put("type", "thread.guest-message.dismiss")
        put("commandId", commandId)
        put("threadId", threadId)
        put("guestMessageId", guestMessageId)
    }

    /** The server runs the guest's own text and files; the owner's client sends none of its own. */
    fun send(commandId: String, threadId: String, guestMessageId: String): JsonObject = buildJsonObject {
        put("type", "message.dispatch")
        put("commandId", commandId)
        put("threadId", threadId)
        put("messageId", commandId)
        put("guestMessageId", guestMessageId)
        put("text", "")
        put("attachments", JsonArray(emptyList()))
        put("dispatchMode", buildJsonObject { put("type", "queue_after_active") })
        put("createdBy", "user")
        put("creationSource", "mobile")
    }

    /** The create error carries only a reason on the wire; its text is derived from the reason. */
    fun createFailureMessage(error: JsonObject): String? {
        if ((error["_tag"] as? JsonPrimitive)?.contentOrNull != "GuestShareCreateError") return null
        return when ((error["reason"] as? JsonPrimitive)?.contentOrNull) {
            "disabled" -> "Guest links are off. Turn them on in Settings > Labs."
            "connect_unavailable" -> "Guest links need a running T3 Connect tunnel."
            "thread_unavailable" -> "This thread cannot be shared."
            "read_failed" -> "Failed to read the shared thread."
            "changed" -> "The guest link changed while it was being created."
            else -> "Could not create a guest link."
        }
    }
}
