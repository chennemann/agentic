package de.chennemann.agentic.domain.guestshare

import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestShareServiceTest {
    private val fixture = GuestShareFixture()
    private val service = fixture.service
    private val calls = fixture.calls

    @Test
    fun `links need the access permission before any setting matters`() = runTest {
        fixture.permissions = listOf("orchestration:operate", "settings:write")
        assertEquals(GuestShareAvailability.MissingPermission, service.availability())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `labs setting off offers enabling only with settings permission`() = runTest {
        fixture.settings = """{"guestSharingEnabled":false}"""
        assertEquals(GuestShareAvailability.Disabled(canEnable = true), service.availability())
        fixture.permissions = listOf("access:write")
        assertEquals(GuestShareAvailability.Disabled(canEnable = false), service.availability())
        fixture.settings = """{}"""
        assertEquals(GuestShareAvailability.Disabled(canEnable = false), service.availability())
    }

    @Test
    fun `available environment creates a link for the thread`() = runTest {
        assertEquals(GuestShareAvailability.Available, service.availability())
        val link = service.create("thread-1")
        assertEquals("https://abc.t3.example/guest#share-1.sig", link.url)
        assertEquals("share-1", link.share.id)
        assertEquals("guestShare.create", calls.last().first)
        assertEquals("thread-1", calls.last().second.getValue("threadId").jsonPrimitive.content)
    }

    @Test
    fun `enabling patches only the guest sharing setting`() = runTest {
        service.enableSharing()
        val (method, input) = calls.single()
        assertEquals("server.updateSettings", method)
        assertEquals(setOf("guestSharingEnabled"), input.getValue("patch").jsonObject.keys)
    }

    @Test
    fun `approve reject and stop dispatch distinct orchestration commands`() = runTest {
        service.approve("thread-1", "g-1")
        service.reject("thread-1", "g-2")
        service.stop("thread-1")
        assertEquals(List(3) { "orchestration.dispatchCommand" }, calls.map { it.first })
        assertEquals(
            listOf("message.dispatch", "thread.guest-message.dismiss", "thread.guest-share.close"),
            calls.map { it.second.getValue("type").jsonPrimitive.content }
        )
        assertEquals(
            listOf("command-1", "command-2", "command-3"),
            calls.map { it.second.getValue("commandId").jsonPrimitive.content }
        )
        assertEquals("g-1", calls[0].second.getValue("guestMessageId").jsonPrimitive.content)
        assertEquals("g-2", calls[1].second.getValue("guestMessageId").jsonPrimitive.content)
    }

    @Test
    fun `focused share follows the live projection and keeps only waiting messages`() = runTest {
        assertNull(service.focusedShare.first())
        fixture.focus(GuestShareJson)
        val focused = service.focusedShare.first()!!
        assertEquals("thread-1", focused.threadId)
        assertEquals("Alex", focused.guestName)
        assertEquals(Instant.parse("2026-10-20T10:00:00Z"), focused.expiresAt)
        assertEquals(listOf("g-1"), focused.pending.map { it.id })
        val attachment = focused.pending.single().attachments.single()
        assertTrue(attachment.isImage)
        assertEquals("data:image/png;base64,AAAA", service.attachmentPreview(attachment))
        fixture.focus(null)
        assertNull(service.focusedShare.first())
    }
}
