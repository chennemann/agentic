package de.chennemann.agentic.domain.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingBufferTest {
    @Test
    fun claimMatchesByTextAndReturnsSort() {
        val pending = PendingBuffer()
        pending.add("k", MessageState(id = "1", role = "user", text = "foo", sort = "z-1"), 1)
        pending.add("k", MessageState(id = "2", role = "user", text = "bar", sort = "z-2"), 1)

        val value = pending.claim("k", "bar")

        assertNotNull(value)
        assertEquals("2", value?.id)
        assertEquals("z-2", value?.sort)
    }

    @Test
    fun trimRemovesExpiredPending() {
        val pending = PendingBuffer()
        pending.add("k", MessageState(id = "1", role = "user", text = "foo", sort = "z-1"), 1)

        val changed = pending.trim("k")
        val empty = pending.trim("k")

        assertFalse(changed)
        assertTrue(empty)
        assertNull(pending.list("k"))
    }
}
