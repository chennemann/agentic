package de.chennemann.agentic.t3.contract

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthSessionTest {
    private fun session(fields: String): AuthSession = T3Json.decodeFromString(
        """{"authenticated":true,"auth":{"policy":"required","bootstrapMethods":[],"sessionMethods":[],"sessionCookieName":"session"},$fields}"""
    )

    @Test
    fun `granular grants are read from permissions rather than filtered scopes`() {
        val session = session(""""scopes":["orchestration:read"],"permissions":["filesystem:read"]""")
        assertTrue(session.grants("filesystem:read"))
        assertFalse(session.grants("orchestration:read"))
        assertFalse(session.copy(authenticated = false).grants("filesystem:read"))
    }

    @Test
    fun `explicitly empty permissions do not inherit scopes`() {
        assertFalse(session(""""scopes":["filesystem:read"],"permissions":[]""").grants("filesystem:read"))
    }

    @Test
    fun `absent permissions uses scopes without inferring additional grants`() {
        val session = session(""""scopes":["orchestration:read"]""")
        assertTrue(session.grants("orchestration:read"))
        assertFalse(session.grants("filesystem:read"))
    }
}
