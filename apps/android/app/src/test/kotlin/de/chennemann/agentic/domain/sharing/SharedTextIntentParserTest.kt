package de.chennemann.agentic.domain.sharing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SharedTextIntentParserTest {
    @Test
    fun `shared text and urls are sanitized and fingerprinted deterministically`() {
        val first = SharedTextIntentParser.parse("android.intent.action.SEND", "text/plain", " \u0000https://example.test/a \n")
        val repeated = SharedTextIntentParser.parse("android.intent.action.SEND", "text/plain", "https://example.test/a")

        assertEquals("https://example.test/a", (first as SharedTextParseResult.Accepted).text)
        assertEquals(first.fingerprint, (repeated as SharedTextParseResult.Accepted).fingerprint)
        assertNotEquals("https://example.test/a", first.fingerprint)
    }

    @Test
    fun `empty and unsupported shares are rejected truthfully`() {
        assertEquals(
            "The shared content is empty.",
            (SharedTextIntentParser.parse("android.intent.action.SEND", "text/plain", "  ") as SharedTextParseResult.Rejected).message,
        )
        assertInstanceOf(
            SharedTextParseResult.Rejected::class.java,
            SharedTextIntentParser.parse("android.intent.action.SEND", "image/png", "ignored"),
        )
    }
}
