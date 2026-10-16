package de.chennemann.agentic.ui.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalInputTest {
    @Test fun `paste strips unsafe controls and normalizes line endings as upstream does`() {
        assertEquals(
            "one\rtwo\rthree\rfour\t [201~ ",
            encodeTerminalPaste("one\r\ntwo\nthree\rfour\t\u001b[201~\u0003")
        )
        assertEquals("caf\u00e9 \uD83D\uDE00", encodeTerminalPaste("caf\u00e9 \uD83D\uDE00"))
    }

    @Test fun `ctrl modifier matches upstream alphabet and punctuation mappings`() {
        ('a'..'z').forEach { char ->
            assertEquals((char.code - 96).toChar().toString(), applyTerminalCtrlModifier(char.uppercase()))
        }
        "@[\\]^_?".toList().zip(listOf(0, 27, 28, 29, 30, 31, 127)).forEach { (char, code) ->
            assertEquals(code.toChar().toString(), applyTerminalCtrlModifier(char.toString()))
        }
        assertEquals("", applyTerminalCtrlModifier(""))
        assertEquals("1", applyTerminalCtrlModifier("1"))
    }
}
