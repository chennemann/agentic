package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.domain.session.MessageState
import de.chennemann.agentic.domain.session.ToolCallState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConversationRenderMapperTest {
    private val mapper = ConversationRenderMapper()

    @Test
    fun mapsTurnAsUserThenToolsThenSystem() {
        val calls = listOf(
            ToolCallState(
                id = "tool-1",
                title = "bash",
                status = "completed",
                details = listOf("ok"),
            ),
        )
        val messages = listOf(
            MessageState(id = "u1", role = "user", text = "Question", sort = "1"),
            MessageState(id = "a1", role = "assistant", text = "System answer", sort = "2", toolCalls = calls),
            MessageState(id = "a2", role = "assistant", text = "Extra detail", sort = "3"),
        )

        val value = mapper.map(messages)

        assertEquals(1, value.size)
        assertEquals("u1", value.single().id)
        assertEquals("Question", value.single().userText)
        assertEquals(listOf("tool-1"), value.single().toolCalls.map { it.id })
        assertEquals(listOf("System answer", "Extra detail"), value.single().systemTexts)
    }

    @Test
    fun startsTurnWithoutUserWhenSystemLeads() {
        val messages = listOf(
            MessageState(id = "a1", role = "assistant", text = "Hello", sort = "1"),
            MessageState(id = "u1", role = "user", text = "Question", sort = "2"),
        )

        val value = mapper.map(messages)

        assertEquals(2, value.size)
        assertEquals("a1", value[0].id)
        assertEquals(null, value[0].userText)
        assertEquals(listOf("Hello"), value[0].systemTexts)
        assertEquals("u1", value[1].id)
        assertEquals("Question", value[1].userText)
    }

    @Test
    fun filtersStreamingOnlyAssistantMessages() {
        val messages = listOf(
            MessageState(id = "u1", role = "user", text = "Question", sort = "1"),
            MessageState(id = "a1", role = "assistant", text = "(streaming...)", sort = "2"),
            MessageState(id = "a2", role = "assistant", text = "", sort = "3"),
            MessageState(id = "a3", role = "assistant", text = "Final", sort = "4"),
        )

        val value = mapper.map(messages)

        assertEquals(1, value.size)
        assertEquals(listOf("Final"), value.single().systemTexts)
    }

    @Test
    fun aggregatesToolCallsAcrossAssistantMessagesInTurn() {
        val messages = listOf(
            MessageState(id = "u1", role = "user", text = "Question", sort = "1"),
            MessageState(
                id = "a1",
                role = "assistant",
                text = "",
                sort = "2",
                toolCalls = listOf(ToolCallState(id = "t1", title = "one", details = emptyList())),
            ),
            MessageState(
                id = "a2",
                role = "assistant",
                text = "",
                sort = "3",
                toolCalls = listOf(ToolCallState(id = "t2", title = "two", details = emptyList())),
            ),
            MessageState(id = "u2", role = "user", text = "Second", sort = "4"),
        )

        val value = mapper.map(messages)

        assertEquals(2, value.size)
        assertEquals(listOf("t1", "t2"), value[0].toolCalls.map { it.id })
        assertEquals("u2", value[1].id)
    }

    @Test
    fun mapsTurnTimestamps() {
        val messages = listOf(
            MessageState(id = "u1", role = "user", text = "Question", sort = "1", createdAt = 1_000L),
            MessageState(id = "a1", role = "assistant", text = "Done", sort = "2", completedAt = 7_000L),
        )

        val value = mapper.map(messages)

        assertEquals(1_000L, value.single().startedAt)
        assertEquals(7_000L, value.single().completedAt)
    }
}
