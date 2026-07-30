package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationEvent
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.PortableJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectionReducersTest {
    @Test
    fun `shell reducer applies every canonical variant and removes deleted entities`() {
        val items = shellItems()
        var state = ProjectionState<de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot>()

        items.forEach { item ->
            val result = ShellProjectionReducer.reduce(state, item)
            state = when (result) {
                is Reduction.Applied -> result.state
                is Reduction.Ignored -> result.state
                is Reduction.Gap -> error("Unexpected fixture gap")
            }
        }

        assertEquals(44, state.sequence)
        assertTrue(state.value?.projects.orEmpty().isEmpty())
        assertTrue(state.value?.threads.orEmpty().isEmpty())
        assertTrue(state.synchronized)
    }

    @Test
    fun `duplicate is ignored and a sparse global sequence is applied`() {
        val items = shellItems()
        val snapshot = ShellProjectionReducer.reduce(
            ProjectionState(),
            items[0],
        ) as Reduction.Applied
        val applied = ShellProjectionReducer.reduce(snapshot.state, items[1]) as Reduction.Applied

        val duplicate = ShellProjectionReducer.reduce(applied.state, items[1])
        val sparse = ShellProjectionReducer.reduce(applied.state, items[3])

        assertInstanceOf(Reduction.Ignored::class.java, duplicate)
        assertInstanceOf(Reduction.Applied::class.java, sparse)
        assertEquals(43, (sparse as Reduction.Applied).state.sequence)
    }

    @Test
    fun `thread reducer applies sparse global sequences without snapshot recovery`() {
        val items = threadItems()
        val snapshot = ThreadProjectionReducer.reduce(
            ProjectionState(),
            items[0],
        ) as Reduction.Applied
        val event = items.filterIsInstance<OrchestrationThreadStreamItem.Event>().first()
        val sparse = event.copy(event = event.event.copy(sequence = 1_000))

        val result = ThreadProjectionReducer.reduce(snapshot.state, sparse)

        assertInstanceOf(Reduction.Applied::class.java, result)
        assertEquals(1_000, (result as Reduction.Applied).state.sequence)
    }

    @Test
    fun `thread reducer accumulates streaming message fragments`() {
        val items = threadItems()
        val snapshot = ThreadProjectionReducer.reduce(
            ProjectionState(),
            items[0],
        ) as Reduction.Applied
        val event = items.filterIsInstance<OrchestrationThreadStreamItem.Event>().first {
            it.event.type == "thread.message-sent"
        }
        val fragment = event.withMessageText(" streamed", sequence = 44)
        val terminalFragment = event.withMessageText(" together", sequence = 45, streaming = false)
        val accumulated = event.withMessageText(
            "Generating streamed together!",
            sequence = 46,
            streaming = false,
        )

        val afterFragment = ThreadProjectionReducer.reduce(snapshot.state, fragment) as Reduction.Applied
        val afterTerminal = ThreadProjectionReducer.reduce(
            afterFragment.state,
            terminalFragment,
        ) as Reduction.Applied
        val afterAccumulated = ThreadProjectionReducer.reduce(
            afterTerminal.state,
            accumulated,
        ) as Reduction.Applied

        assertEquals(
            "Generating streamed together",
            afterTerminal.state.value?.thread?.messages
                ?.first { it.id == "message-assistant" }
                ?.text,
        )
        assertEquals(
            "Generating streamed together!",
            afterAccumulated.state.value?.thread?.messages
                ?.first { it.id == "message-assistant" }
                ?.text,
        )
    }

    @Test
    fun `thread reducer preserves completed turn diff checkpoints`() {
        val snapshot = ThreadProjectionReducer.reduce(
            ProjectionState(),
            threadItems().first(),
        ) as Reduction.Applied
        val payload = PortableJson.parseToJsonElement(
            """
            {
              "threadId": "thread-golden",
              "turnId": "turn-golden",
              "checkpointTurnCount": 1,
              "checkpointRef": "checkpoint-1",
              "status": "ready",
              "files": [
                {
                  "path": "app/src/main/App.kt",
                  "kind": "modified",
                  "additions": 8,
                  "deletions": 2
                }
              ],
              "assistantMessageId": "message-assistant",
              "completedAt": "2026-01-01T00:00:02.000Z"
            }
            """.trimIndent(),
        ).jsonObject
        val event = OrchestrationThreadStreamItem.Event(
            OrchestrationEvent(
                aggregateId = "thread-golden",
                aggregateKind = "thread",
                eventId = "event-diff",
                commandId = "command-diff",
                correlationId = "command-diff",
                sequence = 41,
                type = "thread.turn-diff-completed",
                payload = payload,
                occurredAt = "2026-01-01T00:00:02.000Z",
            ),
        )

        val result = ThreadProjectionReducer.reduce(snapshot.state, event) as Reduction.Applied
        val checkpoint = result.state.value?.thread?.checkpoints?.single()?.jsonObject

        assertEquals("turn-golden", result.state.value?.thread?.latestTurn?.turnId)
        assertEquals("completed", result.state.value?.thread?.latestTurn?.state)
        assertEquals("turn-golden", checkpoint?.get("turnId")?.jsonPrimitive?.content)
        assertEquals(
            "app/src/main/App.kt",
            checkpoint
                ?.get("files")
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("path")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `cached snapshot cannot replace newer live shell`() {
        val snapshot = (shellItems().first() as OrchestrationShellStreamItem.Snapshot).snapshot
        val live = ShellProjectionReducer.snapshot(ProjectionState(), snapshot, ProjectionSource.LIVE)
        val staleCache = ShellProjectionReducer.snapshot(
            live,
            snapshot.copy(snapshotSequence = snapshot.snapshotSequence - 1),
            ProjectionSource.CACHE,
        )

        assertSame(live, staleCache)
    }

    @Test
    fun `thread reducer applies every canonical event and preserves unknown activity`() {
        val items = threadItems()
        var state = ProjectionState<de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot>()

        items.forEach { item ->
            val result = ThreadProjectionReducer.reduce(state, item)
            state = when (result) {
                is Reduction.Applied -> result.state
                is Reduction.Ignored -> result.state
                is Reduction.Gap -> error("Unexpected fixture gap ${result.expected}/${result.received}")
            }
        }

        assertEquals(56, state.sequence)
        assertTrue(state.synchronized)
        assertTrue(state.value?.thread?.activities.orEmpty().any { it.kind == "future.additive-activity" })
        assertEquals("Renamed golden thread", state.value?.thread?.title)
    }

    private fun shellItems(): List<OrchestrationShellStreamItem> = PortableJson.decodeFromString(
        ListSerializer(OrchestrationShellStreamItem.serializer()),
        fixture("shell-stream-items.json"),
    )

    private fun threadItems(): List<OrchestrationThreadStreamItem> = PortableJson.decodeFromString(
        ListSerializer(OrchestrationThreadStreamItem.serializer()),
        fixture("thread-stream-items.json"),
    )

    private fun fixture(name: String): String = checkNotNull(
        javaClass.getResource("/t3-portable-v1/$name"),
    ).readText()
}

private fun OrchestrationThreadStreamItem.Event.withMessageText(
    text: String,
    sequence: Long,
    streaming: Boolean = true,
): OrchestrationThreadStreamItem.Event = copy(
    event = event.copy(
        sequence = sequence,
        payload = JsonObject(
            event.payload + mapOf(
                "text" to JsonPrimitive(text),
                "streaming" to JsonPrimitive(streaming),
            ),
        ),
    ),
)
