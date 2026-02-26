package de.chennemann.opencode.mobile.domain.session

import de.chennemann.opencode.mobile.domain.message.MessageDecorator
import de.chennemann.opencode.mobile.domain.message.MessagePart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FocusedMessageProjectorTest {
    private val projector = FocusedMessageProjector(MessageDecorator())

    @Test
    fun overlaysPendingAndSorts() {
        val key = "server::session"
        val base = listOf(
            MessageState(id = "1", role = "assistant", text = "a", sort = "2"),
            MessageState(id = "2", role = "assistant", text = "b", sort = "4"),
        )
        val staged = mapOf(
            "$key::2" to MessageState(id = "2", role = "assistant", text = "override", sort = "3"),
        )
        val pending = listOf(
            MessageState(id = "3", role = "user", text = "pending", sort = "1"),
        )

        val value = projector.project(
            key = key,
            base = base,
            staged = staged,
            pending = pending,
            parts = emptyMap(),
        )

        assertEquals(listOf("3", "1", "2"), value.map { it.id })
        assertEquals("override", value.last().text)
    }

    @Test
    fun decoratesAssistantWithParts() {
        val key = "server::session"
        val base = listOf(
            MessageState(id = "1", role = "assistant", text = "(streaming...)", sort = "1"),
        )
        val parts = mapOf(
            "$key::1" to linkedMapOf(
                "text" to MessagePart(
                    id = "text",
                    type = "text",
                    text = "rendered",
                ),
            ),
        )

        val value = projector.project(
            key = key,
            base = base,
            staged = emptyMap(),
            pending = null,
            parts = parts,
        )

        assertEquals("rendered", value.single().text)
    }

    @Test
    fun keepsToolCallsWhenPartsTemporarilyMissing() {
        val key = "server::session"
        val base = listOf(
            MessageState(id = "1", role = "assistant", text = "(streaming...)", sort = "1"),
        )
        val withParts = mapOf(
            "$key::1" to linkedMapOf(
                "tool" to MessagePart(
                    id = "tool",
                    type = "tool",
                    text = "",
                    tool = "bash",
                ),
            ),
        )

        val first = projector.project(
            key = key,
            base = base,
            staged = emptyMap(),
            pending = null,
            parts = withParts,
        )

        val second = projector.project(
            key = key,
            base = base,
            staged = emptyMap(),
            pending = null,
            parts = emptyMap(),
        )

        assertTrue(first.single().toolCalls.isNotEmpty())
        assertTrue(second.single().toolCalls.isNotEmpty())
    }
}
