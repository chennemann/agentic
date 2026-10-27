package de.chennemann.agentic.t3.contract

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestShareContractsTest {
    @Test
    fun `thread projection share decodes waiting messages with their files and tolerates additive fields`() {
        val share = GuestShareContracts.share(
            json(
                """{"id":"thread-1","title":"Shared","guestShare":{"id":"share-1","host":"abc.t3.example",
                "createdAt":"2026-10-19T10:00:00.000Z","expiresAt":"2026-10-20T10:00:00.000Z","guestName":"Alex",
                "futureField":true,"messages":[
                  {"id":"g-1","text":"Try the other branch","status":"pending","submittedAt":"2026-10-19T10:05:00.000Z",
                   "attachments":[{"type":"image","id":"att-1","name":"shot.png","mimeType":"image/png","sizeBytes":2048}]},
                  {"id":"g-2","text":"Older","status":"sent","submittedAt":"2026-10-19T10:01:00.000Z"}
                ]}}"""
            )
        )!!
        assertEquals("share-1", share.id)
        assertEquals("Alex", share.guestName)
        assertEquals(listOf("pending", "sent"), share.messages.map { it.status })
        assertEquals("att-1", share.messages.first().attachments.single().id)
        assertTrue(share.messages.last().attachments.isEmpty())
    }

    @Test
    fun `absent null and unreadable shares mean the thread is not shared`() {
        assertNull(GuestShareContracts.share(json("""{"id":"thread-1"}""")))
        assertNull(GuestShareContracts.share(json("""{"id":"thread-1","guestShare":null}""")))
        assertNull(GuestShareContracts.share(json("""{"id":"thread-1","guestShare":{"id":"share-1"}}""")))
    }

    @Test
    fun `labs setting is read from server settings and defaults off`() {
        assertTrue(GuestShareContracts.sharingEnabled(json("""{"guestSharingEnabled":true,"other":1}""")))
        assertFalse(GuestShareContracts.sharingEnabled(json("""{"guestSharingEnabled":false}""")))
        assertFalse(GuestShareContracts.sharingEnabled(json("""{}""")))
        assertEquals(
            true.toString(),
            GuestShareContracts.enableSettingsPatch().getValue("patch").jsonObject
                .getValue("guestSharingEnabled").jsonPrimitive.content
        )
    }

    @Test
    fun `approving dispatches the guest message for the server to send after the active run`() {
        val command = GuestShareContracts.send("command-1", "thread-1", "g-1")
        assertEquals("message.dispatch", command.text("type"))
        assertEquals("g-1", command.text("guestMessageId"))
        assertEquals("command-1", command.text("messageId"))
        assertEquals("", command.text("text"))
        assertTrue(command.getValue("attachments").jsonArray.isEmpty())
        assertEquals("queue_after_active", command.getValue("dispatchMode").jsonObject.text("type"))
        assertEquals("user", command.text("createdBy"))
        assertEquals("mobile", command.text("creationSource"))
    }

    @Test
    fun `rejecting and stopping use their own thread commands`() {
        val dismiss = GuestShareContracts.dismiss("command-2", "thread-1", "g-1")
        assertEquals("thread.guest-message.dismiss", dismiss.text("type"))
        assertEquals("g-1", dismiss.text("guestMessageId"))
        val close = GuestShareContracts.close("command-3", "thread-1")
        assertEquals("thread.guest-share.close", close.text("type"))
        assertEquals(setOf("type", "commandId", "threadId"), close.keys)
    }

    @Test
    fun `create failures carry only a reason and are explained by it`() {
        assertEquals(
            "Guest links need a running T3 Connect tunnel.",
            GuestShareContracts.createFailureMessage(
                json("""{"_tag":"GuestShareCreateError","reason":"connect_unavailable"}""")
            )
        )
        assertEquals(
            "Guest links are off. Turn them on in Settings > Labs.",
            GuestShareContracts.createFailureMessage(json("""{"_tag":"GuestShareCreateError","reason":"disabled"}"""))
        )
        assertNull(GuestShareContracts.createFailureMessage(json("""{"_tag":"OtherError","reason":"disabled"}""")))
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun json(value: String): JsonObject = T3Json.parseToJsonElement(value).jsonObject
}
