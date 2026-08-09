package de.chennemann.agentic.domain.shortcuts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ShortcutRouteCodecTest {
    @Test
    fun `provider neutral thread identity round trips exactly`() {
        val route = ShortcutRoute.Thread("env one", "project/1", "thread?1")
        assertEquals(route, (ShortcutRouteCodec.parse(ShortcutRouteCodec.encode(route)) as ShortcutParseResult.Valid).route)
    }

    @Test
    fun `malformed and incomplete identities are rejected truthfully`() {
        assertInstanceOf(ShortcutParseResult.Invalid::class.java, ShortcutRouteCodec.parse("agentic://shortcut/thread?environmentId=e"))
        assertInstanceOf(ShortcutParseResult.Invalid::class.java, ShortcutRouteCodec.parse("https://example.test"))
    }
}
