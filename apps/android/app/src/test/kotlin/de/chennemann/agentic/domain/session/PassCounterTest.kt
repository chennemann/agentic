package de.chennemann.agentic.domain.session

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PassCounterTest {
    @Test
    fun consumeDecrementsAndClears() {
        val pass = PassCounter()
        pass.set("k", "m", 2)

        assertTrue(pass.consume("k", "m"))
        assertTrue(pass.consume("k", "m"))
        assertFalse(pass.consume("k", "m"))
    }

    @Test
    fun removeClearsEntry() {
        val pass = PassCounter()
        pass.set("k", "m", 1)
        pass.remove("k", "m")

        assertFalse(pass.consume("k", "m"))
    }
}
