package de.chennemann.agentic.data.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingUrlParserTest {
    @Test
    fun `direct and hosted pairing links normalize without retaining path or token`() {
        val direct = PairingUrlParser.parse(
            "https://remote.example.test:444/pair#token=PAIRCODE",
            requireCredential = true,
        )
        val hosted = PairingUrlParser.parse(
            "https://app.t3.codes/pair?host=https%3A%2F%2Fremote.example.test%3A444#token=PAIRCODE",
            requireCredential = true,
        )

        assertEquals("https://remote.example.test:444/", direct.baseUrl)
        assertEquals(direct, hosted)
        assertEquals("PAIRCODE", direct.bootstrapCredential)
    }

    @Test
    fun `environment mismatch is rejected`() {
        val target = PairingUrlParser.parse(
            "https://remote.example.test/pair?environmentId=expected#token=PAIRCODE",
            requireCredential = true,
        )

        assertThrows(PairingException.EnvironmentMismatch::class.java) {
            PairingUrlParser.validateEnvironment(target, "other")
        }
    }

    @Test
    fun `private cleartext requires confirmation and public cleartext is rejected`() {
        val privateTarget = PairingUrlParser.parse(
            "http://192.168.1.20:3773/pair#token=PAIRCODE",
            requireCredential = true,
        )

        assertTrue(privateTarget.requiresCleartextConfirmation)
        assertThrows(PairingException.PublicCleartext::class.java) {
            PairingUrlParser.parse(
                "http://8.8.8.8:3773/pair#token=PAIRCODE",
                requireCredential = true,
            )
        }
    }
}
