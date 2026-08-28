package de.chennemann.agentic.data.t3

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientCertificateKeyAlgorithmTest {
    @Test
    fun `matches common TLS key types`() {
        assertTrue(keyAlgorithmMatches("RSA", "RSA"))
        assertTrue(keyAlgorithmMatches("RSA", "RSA_PSS"))
        assertTrue(keyAlgorithmMatches("EC", "EC_RSA"))
        assertTrue(keyAlgorithmMatches("RSASSA-PSS", "RSA"))
        assertFalse(keyAlgorithmMatches("EC", "RSA"))
    }
}
