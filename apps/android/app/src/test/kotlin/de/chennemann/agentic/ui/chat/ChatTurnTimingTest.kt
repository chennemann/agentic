package de.chennemann.agentic.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ChatTurnTimingTest {
    @Test
    fun formats_live_and_completed_turn_durations() {
        val start = Instant.parse("2026-08-28T12:00:00Z")

        assertEquals("9s", formatTurnDuration(start.toString(), null, start.plusSeconds(9).toEpochMilli()))
        assertEquals(
            "2m 5s",
            formatTurnDuration(start.toString(), start.plusSeconds(125).toString(), Long.MAX_VALUE),
        )
        assertEquals(
            "1h 2m",
            formatTurnDuration(start.toString(), start.plusSeconds(3_725).toString(), Long.MAX_VALUE),
        )
    }

    @Test
    fun rejects_invalid_server_timestamps() {
        assertNull(formatTurnDuration("not-an-instant", null, 0L))
    }
}
