package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.T3Json
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
    fun `unified shell removes archived threads and replaces them on reconnect`() {
        val active = shellItems().first() as OrchestrationShellStreamItem.Snapshot
        val archived = active.snapshot.threads.first().copy(archivedAt = "now")
        val initial = active.copy(snapshot = active.snapshot.copy(threads = listOf(archived)))
        val loaded = (ShellProjectionReducer.reduce(ProjectionState(), initial) as Reduction.Applied).state
        val removed = (ShellProjectionReducer.reduce(loaded,
            OrchestrationShellStreamItem.ThreadRemoved(initial.snapshot.snapshotSequence + 1, archived.id)) as Reduction.Applied).state
        assertTrue(removed.value!!.threads.isEmpty())
        val replacement = initial.copy(snapshot = initial.snapshot.copy(threads = emptyList(), snapshotSequence = initial.snapshot.snapshotSequence + 2))
        val refreshed = (ShellProjectionReducer.reduce(loaded, replacement) as Reduction.Applied).state
        assertTrue(refreshed.value!!.threads.isEmpty())
        assertEquals(replacement.snapshot.snapshotSequence, refreshed.sequence)
    }

    @Test
    fun `v2 history retains streamed text and stays present after the next event`() {
        val metadata = """{"id":"thread","projectId":"project","title":"Title","createdAt":"now","updatedAt":"now"}"""
        fun row(id: String, text: String, position: Long) = de.chennemann.agentic.t3.contract.V2VisibleItem(
            position, "local", "thread", id, T3Json.parseToJsonElement(
                """{"id":"$id","messageId":"$id","type":"assistant_message","text":"$text","ordinal":$position,"updatedAt":"now"}""",
            ).jsonObject,
        )
        val live = de.chennemann.agentic.t3.contract.V2Projection(
            T3Json.parseToJsonElement(metadata).jsonObject, updatedAt = "now", visibleTurnItems = listOf(row("new", "streamed", 2)),
        )
        val snapshot = de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot(
            10, live.detail(), de.chennemann.agentic.t3.contract.OrchestrationThreadDetailPage("cursor", true, 10), live,
        )
        val current = ThreadProjectionReducer.snapshot(ProjectionState(), snapshot, ProjectionSource.LIVE)
        val old = live.copy(visibleTurnItems = listOf(row("old", "older", 0), row("new", "stale", 1)))
        val incoming = snapshot.copy(snapshotSequence = 9, projection = old, thread = old.detail(), page = snapshot.page!!.copy(beforeCursor = null, hasMore = false))
        assertEquals(null, ThreadProjectionReducer.mergeHistory(current, incoming, "wrong-cursor", current.historyGeneration))
        assertEquals(null, ThreadProjectionReducer.mergeHistory(current, incoming, "cursor", current.historyGeneration - 1))
        val merged = requireNotNull(ThreadProjectionReducer.mergeHistory(current, incoming, "cursor", current.historyGeneration))
        assertEquals(listOf("older", "streamed"), merged.value!!.thread.messages.map { it.text })
        val event = OrchestrationThreadStreamItem.Event(11, de.chennemann.agentic.t3.contract.V2EventPayload(
            "turn-item.updated", row("new", "streamed completion", 2).item,
        ))
        val updated = (ThreadProjectionReducer.reduce(merged, event) as Reduction.Applied).state
        assertEquals(listOf("older", "streamed completion"), updated.value!!.thread.messages.map { it.text })
        assertEquals(11, updated.sequence)
        assertInstanceOf(Reduction.Ignored::class.java, ThreadProjectionReducer.reduce(updated, event))
        val unknown = OrchestrationThreadStreamItem.Event(12, de.chennemann.agentic.t3.contract.V2EventPayload("future.updated"))
        assertEquals(12, (ThreadProjectionReducer.reduce(updated, unknown) as Reduction.Applied).state.sequence)
    }

    @Test
    fun `repository enrichment cannot erase threads or advance the shell cursor`() {
        val snapshot = (shellItems().first() as OrchestrationShellStreamItem.Snapshot).snapshot
        val current = ShellProjectionReducer.snapshot(ProjectionState(), snapshot, ProjectionSource.LIVE)
        val enrichment = de.chennemann.agentic.t3.contract.V2Orchestration.shellItem(
            T3Json.parseToJsonElement("""{"kind":"snapshot","snapshot":{"schemaVersion":2,"snapshotSequence":999,"projects":[],"threads":[],"archivedThreads":[]},"resolvedRepositoryIdentityRoots":["/repo"]}"""),
        )
        val reduced = ShellProjectionReducer.reduce(current, enrichment)
        assertInstanceOf(Reduction.Ignored::class.java, reduced)
        assertSame(current, (reduced as Reduction.Ignored).state)
    }

    @Test
    fun `history rewrite invalidates in flight pages and requires a fresh snapshot`() {
        val snapshot = (threadItems().first() as OrchestrationThreadStreamItem.Snapshot).snapshot
        val current = ThreadProjectionReducer.snapshot(ProjectionState(), snapshot, ProjectionSource.LIVE)
        val event = threadItems().filterIsInstance<OrchestrationThreadStreamItem.Event>().first().event
            .copy(type = "checkpoint.rollback-requested")
        val result = ThreadProjectionReducer.reduce(current, OrchestrationThreadStreamItem.Event(snapshot.snapshotSequence + 1, event)) as Reduction.Gap
        assertEquals(current.historyGeneration + 1, result.state.historyGeneration)
        assertEquals(false, result.state.synchronized)
    }

    @Test
    fun `configuration events refresh providers and settings and ignore unknown additions`() {
        val config = T3Json.decodeFromString<de.chennemann.agentic.t3.contract.ServerConfig>(
            """{"environment":{"environmentId":"env","label":"Test","platform":{"os":"linux","arch":"x64"},"serverVersion":"1","capabilities":{}},"auth":{"policy":"test","bootstrapMethods":[],"sessionMethods":[],"sessionCookieName":"test"},"providers":[]}""",
        )
        val providers = T3Json.decodeFromString<de.chennemann.agentic.t3.contract.ServerConfigStreamEvent>(
            """{"version":1,"type":"providerStatuses","payload":{"providers":[{"instanceId":"account","models":[]}]}}""",
        )
        val refreshed = ClientConfigReducer.applyEvent(config, providers)
        assertEquals("account", refreshed.providers.single().instanceId)
        val settings = T3Json.decodeFromString<de.chennemann.agentic.t3.contract.ServerConfigStreamEvent>(
            """{"version":1,"type":"settingsUpdated","payload":{"settings":{"addProjectBaseDirectory":"/new"}}}""",
        )
        assertEquals("/new", ClientConfigReducer.applyEvent(refreshed, settings).settings.addProjectBaseDirectory)
        assertSame(refreshed, ClientConfigReducer.applyEvent(refreshed, de.chennemann.agentic.t3.contract.ServerConfigStreamEvent("future")))
    }
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
        val sparse = event.copy(sequence = 1_000)

        val result = ThreadProjectionReducer.reduce(snapshot.state, sparse)

        assertInstanceOf(Reduction.Applied::class.java, result)
        assertEquals(1_000, (result as Reduction.Applied).state.sequence)
    }

    @Test
    fun `thread reducer replaces cumulative streamed text without duplicating fragments`() {
        val items = threadItems()
        val snapshot = ThreadProjectionReducer.reduce(
            ProjectionState(),
            items[0],
        ) as Reduction.Applied
        val event = items.filterIsInstance<OrchestrationThreadStreamItem.Event>().first {
            it.event.type == "turn-item.updated"
        }
        val fragment = event.withMessageText("Generating streamed", sequence = 44)
        val terminalFragment = event.withMessageText("Generating streamed together", sequence = 45, streaming = false)
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
        val event = threadItems().filterIsInstance<OrchestrationThreadStreamItem.Event>().first {
            it.event.type == "checkpoint.captured"
        }
        val result = ThreadProjectionReducer.reduce(snapshot.state, event) as Reduction.Applied
        val checkpoint = result.state.value?.thread?.checkpoints?.single()?.jsonObject

        assertEquals("turn-golden", result.state.value?.thread?.latestTurn?.turnId)
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

        assertEquals(45, state.sequence)
        assertTrue(state.synchronized)
        assertTrue(state.value?.thread?.activities.orEmpty().any { it.kind == "future.additive-activity" })
        assertEquals("Renamed golden thread", state.value?.thread?.title)
    }

    private fun shellItems(): List<OrchestrationShellStreamItem> = T3Json.decodeFromString(
        ListSerializer(OrchestrationShellStreamItem.serializer()),
        fixture("shell-stream-items.json"),
    )

    private fun threadItems(): List<OrchestrationThreadStreamItem> =
        T3Json.parseToJsonElement(fixture("thread-stream-items.json")).jsonArray.map {
            de.chennemann.agentic.t3.contract.V2Orchestration.threadItem(it)
        }

    private fun fixture(name: String): String = checkNotNull(
        javaClass.getResource("/t3-rpc/$name"),
    ).readText()
}

private fun OrchestrationThreadStreamItem.Event.withMessageText(
    text: String,
    sequence: Long,
    streaming: Boolean = true,
): OrchestrationThreadStreamItem.Event = copy(
    sequence = sequence,
    event = event.copy(
        payload = JsonObject(
            event.payload + mapOf(
                "text" to JsonPrimitive(text),
                "streaming" to JsonPrimitive(streaming),
            ),
        ),
    ),
)
