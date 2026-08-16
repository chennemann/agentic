package de.chennemann.agentic.data.t3

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StreamItemCompatibilityTest {
    @Test
    fun `known kind allows additive fields`() {
        val item = Json.parseToJsonElement(
            """{"kind":"event","futureField":{"nested":true}}""",
        )

        assertDoesNotThrow { requireSupportedStreamKind(item, setOf("event")) }
    }

    @Test
    fun `unknown kind fails before decoding`() {
        val item = Json.parseToJsonElement("""{"kind":"future-event","sequence":42}""")

        val failure = assertThrows(T3TransportException.UnsupportedStreamItem::class.java) {
            requireSupportedStreamKind(item, setOf("event"))
        }

        assertEquals("future-event", failure.kind)
    }
}
