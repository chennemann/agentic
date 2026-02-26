package de.chennemann.opencode.mobile.domain.session

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionResolverTest {
    @Test
    fun enforcesCooldownBySessionId() {
        val resolver = SessionResolver(cooldown = 1000)

        assertTrue(resolver.allow("s1", 1000))
        assertFalse(resolver.allow("s1", 1500))
        assertTrue(resolver.allow("s1", 2100))
    }

    @Test
    fun tracksIdsIndependently() {
        val resolver = SessionResolver(cooldown = 1000)

        assertTrue(resolver.allow("a", 1000))
        assertTrue(resolver.allow("b", 1000))
        assertFalse(resolver.allow("a", 1500))
        assertFalse(resolver.allow("b", 1500))
    }
}
