package de.chennemann.agentic.domain.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogRedactorTest {
    @Test
    fun redactsSensitiveTokensAndUrls() {
        val redactor = LogRedactor()
        val value = redactor.redact("token=abc123 https://example.com/private/path Bearer abc123")

        assertTrue(value.contains("[token]"))
        assertTrue(value.contains("[url]"))
        assertTrue(value.contains("Bearer [redacted]"))
    }

    @Test
    fun redactsSensitiveContextKeys() {
        val redactor = LogRedactor()
        val value = redactor.redact(
            mapOf(
                "authorization" to "Bearer secret",
                "endpoint" to "https://example.com",
            )
        )

        assertEquals("[redacted]", value["authorization"])
        assertEquals("[url]", value["endpoint"])
    }
}
