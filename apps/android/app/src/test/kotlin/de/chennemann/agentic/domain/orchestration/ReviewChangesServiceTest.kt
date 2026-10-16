package de.chennemann.agentic.domain.orchestration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewChangesServiceTest {
    @Test fun `only ready distinct checkpoints produce exact upstream turn ranges`() {
        val checkpoints = listOf(
            """{"status":"pending","checkpointTurnCount":4}""",
            """{"status":"ready","checkpointTurnCount":1}""",
            """{"status":"ready","checkpointTurnCount":3}""",
            """{"status":"ready","checkpointTurnCount":3}""",
            """{"status":"error","checkpointTurnCount":2}"""
        ).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(
            listOf(
                ReviewSection("turn:3", "Turn 3", 2, 3),
                ReviewSection("turn:1", "Turn 1", 0, 1),
                ReviewSection("all", "All turns", 0, 3, true)
            ),
            reviewSections(checkpoints)
        )
        assertEquals(emptyList<ReviewSection>(), reviewSections(emptyList()))
    }
}
