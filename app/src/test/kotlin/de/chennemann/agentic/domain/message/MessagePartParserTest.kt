package de.chennemann.agentic.domain.message

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MessagePartParserTest {
    private val parser = MessagePartParser()

    @Test
    fun parsesToolPart() {
        val part = buildJsonObject {
            put("id", "tool-1")
            put("type", "tool")
            put("tool", "bash")
            put("state", buildJsonObject {
                put("status", "completed")
                put("title", "Run command")
                put("output", "ok")
                put("input", buildJsonObject {
                    put("description", "Run tests")
                    put("command", "./gradlew test")
                })
                put("metadata", buildJsonObject {
                    put("command", "./gradlew test")
                })
                put("time", buildJsonObject {
                    put("start", 123)
                    put("end", 456)
                })
            })
        }

        val value = parser.parsePart(part)

        assertEquals("tool-1", value?.id)
        assertEquals("tool", value?.type)
        assertEquals("bash", value?.tool)
        assertEquals("completed", value?.status)
        assertEquals("Run command", value?.title)
        assertEquals("ok", value?.output)
        assertEquals("Run tests", value?.input?.get("description")?.jsonPrimitive?.content)
        assertEquals("./gradlew test", value?.metadata?.get("command")?.jsonPrimitive?.content)
        assertEquals(123L, value?.startedAt)
        assertEquals(456L, value?.completedAt)
    }

    @Test
    fun returnsNullWithoutType() {
        val part = buildJsonObject {
            put("id", "x")
        }

        assertNull(parser.parsePart(part))
    }

    @Test
    fun parsesPatchPartWithFilesSummary() {
        val part = buildJsonObject {
            put("id", "patch-1")
            put("type", "patch")
            put("files", buildJsonArray {
                add(JsonPrimitive("a.kt"))
                add(JsonPrimitive("b.kt"))
            })
        }

        val value = parser.parsePart(part)

        assertEquals("2 files: a.kt, b.kt", value?.text)
    }
}
