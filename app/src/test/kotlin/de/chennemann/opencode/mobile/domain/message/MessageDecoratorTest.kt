package de.chennemann.opencode.mobile.domain.message

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

class MessageDecoratorTest {
    private val decorator = MessageDecorator()

    @Test
    fun decoratesAssistantMessageWithToolCalls() {
        val parts = listOf(
            MessagePart(
                id = "text-1",
                type = "text",
                text = "final answer",
            ),
            MessagePart(
                id = "tool-1",
                type = "tool",
                text = "",
                tool = "bash",
                status = "completed",
                input = buildJsonObject {
                    put("description", "Run tests")
                    put("command", "./gradlew test")
                },
                output = "ok",
            ),
        )

        val value = decorator.decorate("assistant", "(streaming...)", parts)

        assertEquals("final answer", value.text)
        assertEquals(1, value.toolCalls.size)
        assertEquals("Shell", value.toolCalls.first().title)
        assertEquals("./gradlew test", value.toolCalls.first().subtitle)
        assertTrue(value.toolCalls.first().details.any { it.startsWith("Command: ./gradlew test") })
    }

    @Test
    fun includesLoadedFilesForReadToolCall() {
        val parts = listOf(
            MessagePart(
                id = "tool-1",
                type = "tool",
                text = "",
                tool = "read",
                input = buildJsonObject {
                    put("filePath", "/repo/src/main.kt")
                },
                metadata = buildJsonObject {
                    put(
                        "loaded",
                        buildJsonArray {
                            add(JsonPrimitive("/repo/src/main.kt"))
                            add(JsonPrimitive("/repo/src/utils.kt"))
                        },
                    )
                },
            )
        )

        val value = decorator.decorate("assistant", "(streaming...)", parts)

        assertEquals("Read", value.toolCalls.first().title)
        assertEquals("main.kt", value.toolCalls.first().subtitle)
        assertTrue(value.toolCalls.first().details.any { it.contains("Loaded: /repo/src/main.kt") })
    }

    @Test
    fun keepsUserMessageUntouched() {
        val value = decorator.decorate("user", "hello", emptyList())

        assertEquals("hello", value.text)
        assertEquals(0, value.toolCalls.size)
    }

    @Test
    fun extractsSubagentSessionFromTaskToolMetadata() {
        val parts = listOf(
            MessagePart(
                id = "task-1",
                type = "tool",
                text = "",
                tool = "task",
                metadata = buildJsonObject {
                    put("sessionId", "session-subagent-1")
                },
            )
        )

        val value = decorator.decorate("assistant", "(streaming...)", parts)

        assertEquals("Agent", value.toolCalls.single().title)
        assertEquals("session-subagent-1", value.toolCalls.single().sessionId)
        assertTrue(value.toolCalls.single().details.any { it == "Session: session-subagent-1" })
    }
}
