package de.chennemann.agentic.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MdnsEntryTest {
    @Test
    fun formatsUrlForHostnamesWithoutWrapping() {
        val entry = MdnsEntry(
            name = "opencode-host",
            host = "demo.local",
            port = 4096,
        )

        assertEquals("http://demo.local:4096", entry.url)
    }

    @Test
    fun wrapsIpv6AndStripsZoneIdentifier() {
        val entry = MdnsEntry(
            name = "opencode-ipv6",
            host = "fe80::1%wlan0",
            port = 4096,
        )

        assertEquals("http://[fe80::1]:4096", entry.url)
    }

    @Test
    fun keepsBracketedIpv6Stable() {
        val entry = MdnsEntry(
            name = "opencode-ipv6-bracketed",
            host = "[2001:db8::7]",
            port = 7777,
        )

        assertEquals("http://[2001:db8::7]:7777", entry.url)
    }
}
