package de.chennemann.agentic.ui.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalInputDecoderTest {
    @Test fun `split native UTF8 callbacks preserve every code point`() {
        val decoder = TerminalInputDecoder()
        val input = "caf\u00e9 \u754c \uD83D\uDE00 e\u0301\u001b[201~"
        val output = input.toByteArray().joinToString("") { decoder.decode(byteArrayOf(it)) }
        assertEquals(input, output)
    }
}
