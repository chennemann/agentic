package de.chennemann.agentic.domain.notifications

import de.chennemann.agentic.domain.shortcuts.ShortcutRoute
import de.chennemann.agentic.domain.shortcuts.ShortcutRouteCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ThreadNotificationPayloadParserTest {
    @Test
    fun `supported signal maps to a provider neutral notification`() {
        val result = ThreadNotificationPayloadParser.parse(payload()) as ThreadNotificationParseResult.Valid

        assertEquals(ThreadNotificationKind.COMPLETION, result.notification.kind)
        assertEquals(ShortcutRoute.Thread("environment", "project", "thread"), result.notification.route)
    }

    @Test
    fun `unknown kinds and malformed routes are rejected`() {
        assertInstanceOf(
            ThreadNotificationParseResult.Invalid::class.java,
            ThreadNotificationPayloadParser.parse(payload() + ("kind" to "new-provider-event")),
        )
        assertInstanceOf(
            ThreadNotificationParseResult.Invalid::class.java,
            ThreadNotificationPayloadParser.parse(payload() + ("deepLink" to "https://example.test/thread")),
        )
    }

    @Test
    fun `route identity cannot disagree with delivery identity`() {
        val otherRoute = ShortcutRouteCodec.encode(ShortcutRoute.Thread("other", "project", "thread"))
        assertInstanceOf(
            ThreadNotificationParseResult.Invalid::class.java,
            ThreadNotificationPayloadParser.parse(payload() + ("deepLink" to otherRoute)),
        )
    }

    @Test
    fun `delivery invokes publisher only for a valid signal`() {
        val published = mutableListOf<ThreadNotification>()
        val delivery = ThreadNotificationDelivery { published += it; true }

        delivery.receive(payload())
        delivery.receive(payload() + ("updatedAtEpochMillis" to "invalid"))

        assertEquals(1, published.size)
    }

    private fun payload() = mapOf(
        "environmentId" to "environment",
        "projectId" to "project",
        "threadId" to "thread",
        "kind" to "completion",
        "title" to "Task complete",
        "headline" to "The agent finished its work.",
        "updatedAtEpochMillis" to "42",
        "deepLink" to ShortcutRouteCodec.encode(ShortcutRoute.Thread("environment", "project", "thread")),
    )
}
