package de.chennemann.agentic.ui.guestshare

import de.chennemann.agentic.domain.guestshare.GuestAttachment
import de.chennemann.agentic.domain.guestshare.GuestMessage
import de.chennemann.agentic.domain.guestshare.GuestShare
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestSharePresentationTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val share = GuestShare(
        threadId = "thread-1",
        id = "share-1",
        host = "abc.t3.example",
        expiresAt = Instant.parse("2026-10-20T10:00:00Z"),
        guestName = null,
        pending = listOf(
            GuestMessage(
                id = "g-1",
                text = "Try main",
                submittedAt = now,
                attachments = listOf(
                    GuestAttachment("att-1", "shot.png", "image/png", 2048),
                    GuestAttachment("att-2", "log.txt", "text/plain", 12),
                ),
            ),
        ),
    )

    @Test
    fun `unredeemed link shows its expiry and waiting messages from an unnamed guest`() {
        val state = guestShareUiState(share, now, busyMessageIds = setOf("g-1"), previews = mapOf("att-1" to "data:x"))
        val active = state.active!!
        assertEquals("Guest link active · not opened yet", active.status)
        assertEquals("Expires in 22 h", active.expiry)
        assertFalse(active.expired)
        val message = state.pendingMessages.single()
        assertEquals("Guest", message.guestName)
        assertTrue(message.busy)
        assertEquals(listOf(true, false), message.attachments.map { it.isImage })
        assertEquals("data:x", message.attachments.first().previewDataUrl)
        assertEquals("2 KiB", message.attachments.first().sizeLabel)
    }

    @Test
    fun `redeemed and expired links are labelled for the owner`() {
        val named = guestShareUiState(share.copy(guestName = "Alex"), now)
        assertEquals("Alex is watching as a guest", named.active!!.status)
        assertEquals("Alex", named.pendingMessages.single().guestName)
        val expired = guestShareUiState(share.copy(guestName = "Alex"), Instant.parse("2026-10-20T10:00:00Z"))
        assertTrue(expired.active!!.expired)
        assertEquals("Guest link expired", expired.active!!.status)
    }

    @Test
    fun `no share shows nothing`() {
        val state = guestShareUiState(null, now)
        assertNull(state.active)
        assertNull(state.threadId)
        assertTrue(state.pendingMessages.isEmpty())
    }

    @Test
    fun `expiry labels round to readable units`() {
        assertEquals("Expires in 24 h", guestShareExpiryLabel(now.plusSeconds(24 * 3600), now))
        assertEquals("Expires in 2 h 30 min", guestShareExpiryLabel(now.plusSeconds(150 * 60), now))
        assertEquals("Expires in 5 min", guestShareExpiryLabel(now.plusSeconds(5 * 60 + 20), now))
        assertEquals("Expires in under a minute", guestShareExpiryLabel(now.plusSeconds(30), now))
        assertEquals("Expired 3 h ago", guestShareExpiryLabel(now.minusSeconds(3 * 3600), now))
        assertEquals("Expired under a minute ago", guestShareExpiryLabel(now, now))
        assertEquals("Expiry unknown", guestShareExpiryLabel(null, now))
    }
}
