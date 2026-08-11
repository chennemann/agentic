package de.chennemann.agentic.data.t3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TransportSecurityTest {
    @Test
    fun `token exchange requests exactly the orchestration read and operate scopes`() {
        assertEquals("orchestration:read orchestration:operate", REQUIRED_T3_SCOPES)
    }

    @Test
    fun `transport diagnostics redact bearer pairing and access credentials`() {
        val secret = "super-secret-value"
        val redacted = redactTransportText(
            "Authorization: Bearer $secret subject_token=$secret access_token=$secret",
            secrets = listOf(secret),
        )

        assertFalse(redacted.contains(secret))
        assertEquals(3, Regex("\\[redacted]").findAll(redacted).count())
    }
}
