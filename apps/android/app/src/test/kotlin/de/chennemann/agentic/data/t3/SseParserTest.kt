package de.chennemann.agentic.data.t3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SseParserTest {
    @Test
    fun `comments ids CRLF and multiline data follow SSE framing`() {
        val parser = SseParser()

        assertEquals(null, parser.feedLine(": keepalive\r"))
        assertEquals(null, parser.feedLine("id: 42\r"))
        assertEquals(null, parser.feedLine("event: message"))
        assertEquals(null, parser.feedLine("data: {\"line\":1,"))
        assertEquals(null, parser.feedLine("data: \"next\":2}"))
        val event = parser.feedLine("")

        assertEquals("42", event?.id)
        assertEquals("message", event?.event)
        assertEquals("{\"line\":1,\n\"next\":2}", event?.data)
    }

    @Test
    fun `collector cancellation closes the streaming producer`() = runTest {
        val cancelled = CompletableDeferred<Boolean>()
        val stream = flow {
            try {
                emit("first")
                awaitCancellation()
            } finally {
                cancelled.complete(true)
            }
        }

        stream.take(1).collect {}

        assertTrue(cancelled.await())
    }
}
