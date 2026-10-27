package de.chennemann.agentic.t3.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fixtures mirror the server's orchestrationV2IdTable and boundedSnapshotTransport tests. */
class V2WireCompactionTest {
    @Test
    fun `ID table restores record slots exactly, numbers included`() {
        val wire = json(
            """{"rows":[
            {"id":0,"count":7,"parentIds":[1],"item":{"ownerId":1,"legacyId":2}},
            {"id":1,"count":3,"parentIds":[],"item":{"ownerId":null,"legacyId":3}}
            ],"ids":["row:a","row:b",3,0]}"""
        )
        assertEquals(
            json(
                """{"rows":[
                {"id":"row:a","count":7,"parentIds":["row:b"],"item":{"ownerId":"row:b","legacyId":3}},
                {"id":"row:b","count":3,"parentIds":[],"item":{"ownerId":null,"legacyId":0}}
                ]}"""
            ),
            V2WireCompaction.expandIds(wire, container = null)
        )
    }

    @Test
    fun `ID table encoder in this test matches the server's wire form`() {
        val value = json(
            """{"rows":[
            {"id":"row:a","count":7,"parentIds":["row:b"],"item":{"ownerId":"row:b","legacyId":3}},
            {"id":"row:b","count":3,"parentIds":[],"item":{"ownerId":null,"legacyId":0}}
            ]}"""
        )
        assertEquals(
            json(
                """{"rows":[
                {"id":0,"count":7,"parentIds":[1],"item":{"ownerId":1,"legacyId":2}},
                {"id":1,"count":3,"parentIds":[],"item":{"ownerId":null,"legacyId":3}}
                ],"ids":["row:a","row:b",3,0]}"""
            ),
            internIds(value, container = null)
        )
    }

    @Test
    fun `ID table rejects an index outside the table, even in an optional slot`() {
        val wire = json(
            """{"rows":[{"id":0,"count":1,"parentIds":[],"mergedId":5,"item":{"ownerId":null,"legacyId":0}}],"ids":["row:a"]}"""
        )
        val failure = assertThrows(SerializationException::class.java) {
            V2WireCompaction.expandIds(wire, container = null)
        }
        assertTrue("mergedId" in failure.message.orEmpty())
    }

    @Test
    fun `plain responses without a table pass through unchanged`() {
        val plain = json("""{"rows":[{"id":"row:a","count":1,"parentIds":[],"item":{"ownerId":null,"legacyId":1}}]}""")
        assertSame(plain, V2WireCompaction.expandIds(plain, container = null))
    }

    @Test
    fun `ID table leaves nested values other than item untouched`() {
        val wire = json(
            """{"projection":{"thread":{"id":0,"projectId":1,"lineage":{"rootThreadId":"root"},"forkedFrom":{"threadId":7}},
            "runs":[{"id":2,"threadId":0,"sourcePlanRef":{"planId":9},"ordinal":1}]},"snapshotSequence":4,"ids":["t","p","r"]}"""
        )
        val expanded = V2WireCompaction.expandIds(wire, container = "projection")
        val projection = expanded.getValue("projection").jsonObject
        assertEquals("t", projection["thread"]!!.jsonObject.text("id"))
        assertEquals("p", projection["thread"]!!.jsonObject.text("projectId"))
        assertEquals(json("""{"threadId":7}"""), projection["thread"]!!.jsonObject["forkedFrom"])
        val run = projection["runs"]!!.jsonArray.single().jsonObject
        assertEquals("r", run.text("id"))
        assertEquals("t", run.text("threadId"))
        assertEquals(json("""{"planId":9}"""), run["sourcePlanRef"])
        assertEquals("1", run.text("ordinal"))
        assertEquals("4", expanded.text("snapshotSequence"))
        assertFalse(expanded.containsKey("ids"))
    }

    @Test
    fun `bounded snapshot with every opt-in restores to the plain snapshot`() {
        val plain = V2Orchestration.snapshot(boundedSnapshot(bodiesProjection(), extra = ""))
        val wire = internIds(
            boundedSnapshot(compactBodiesProjection(), extra = ""","turnItemsOmitLocalVisible":true"""),
            container = "projection"
        )
        assertTrue(wire.getValue("ids").jsonArray.isNotEmpty())
        assertTrue(wire.getValue("projection").jsonObject.getValue("thread").jsonObject.getValue("id").isNumber())

        val decoded = V2Orchestration.snapshot(wire)

        assertEquals(plain, decoded)
        assertEquals(4, decoded.projection.turnItems.size)
        assertEquals(listOf("Please fix the build.", "Fixed the import."), decoded.thread.messages.map { it.text })
        val task = decoded.thread.activities.single { it.id == "turn-item:$BODIES:task" }
        assertEquals("It was src/index.ts.", task.payload.text("detail"))
        assertFalse(decoded.projection.turnItems.any { it.keys.any { key -> key.endsWith("Omitted") } })
    }

    @Test
    fun `fork snapshot restores only local rows and keeps inherited rows as sent`() {
        val plain = V2Orchestration.snapshot(boundedSnapshot(forkProjection(compact = false), extra = ""))
        val wire = internIds(
            boundedSnapshot(forkProjection(compact = true), extra = ""","turnItemsOmitLocalVisible":true"""),
            container = "projection"
        )

        val decoded = V2Orchestration.snapshot(wire)

        assertEquals(plain, decoded)
        assertEquals(
            listOf("inherited", "inherited", "inherited", "synthetic", "local", "local", "local", "local"),
            decoded.projection.visibleTurnItems.map { it.visibility }
        )
        assertEquals((1..4).map { "turn-item:$FORK:fork-$it" }, decoded.projection.turnItems.map { it.text("id") })
    }

    @Test
    fun `long thread keeps the trailing hidden turn item after restored local rows`() {
        val visible = (0 until 3).map { tool(LONG_THREAD, "tool-$it", it + 2) }
        val request = tool(LONG_THREAD, "request", 1).let {
            JsonObject(
                it + ("type" to JsonPrimitive("run_interrupt_request"))
            )
        }
        val plainProjection =
            projection(LONG_THREAD, turnItems = visible + request, rows = visible.map { local(LONG_THREAD, it) })
        val compactProjection =
            projection(LONG_THREAD, turnItems = listOf(request), rows = visible.map { local(LONG_THREAD, it) })
        val plain = V2Orchestration.snapshot(
            boundedSnapshot(plainProjection, extra = ""","historyCursor":"cursor","hasMoreHistory":true""")
        )
        val decoded = V2Orchestration.snapshot(
            internIds(
                boundedSnapshot(
                    compactProjection,
                    extra = ""","historyCursor":"cursor","hasMoreHistory":true,"turnItemsOmitLocalVisible":true"""
                ),
                container = "projection"
            )
        )

        assertEquals(plain, decoded)
        assertEquals("turn-item:$LONG_THREAD:request", decoded.projection.turnItems.last().text("id"))
        assertTrue(decoded.page!!.hasMore)
        assertEquals("cursor", decoded.page!!.beforeCursor)
    }

    @Test
    fun `stream snapshot items expand the table on the projection`() {
        val item = internIds(
            json(
                """{"kind":"snapshot","snapshotSequence":12,"projection":${compactBodiesProjection()},
                "historyCursor":null,"hasMoreHistory":false,"latestLocalTurnOrdinal":4,"turnItemsOmitLocalVisible":true}"""
            ),
            container = "projection"
        )
        val snapshot = (V2Orchestration.threadItem(item) as OrchestrationThreadStreamItem.Snapshot).snapshot
        assertEquals(
            V2Orchestration.snapshot(
                boundedSnapshot(bodiesProjection(), extra = "")
            ).projection.copy(latestLocalTurnOrdinal = 4),
            snapshot.projection
        )
    }

    @Test
    fun `history pages expand the table on top-level records`() {
        val page = json(
            """{"snapshotSequence":9,"items":[${local(
                LONG_THREAD,
                tool(LONG_THREAD, "tool-0", 2)
            )}],"nextCursor":"older","hasMoreHistory":true}"""
        )
        val wire = internIds(page, container = null)
        assertTrue(wire.getValue("items").jsonArray.single().jsonObject.getValue("sourceItemId").isNumber())
        assertEquals(page, V2WireCompaction.expandIds(wire, container = null))
    }

    @Test
    fun `live tail restores bodies the stream sent once`() {
        var projection = V2Orchestration.snapshot(boundedSnapshot(bodiesProjection(), extra = "")).projection
        val reply = message("reply", "assistant", "Fixed the import, and the test.")
        val replyItem = JsonObject(
            bodiesItem(
                "reply",
                5,
                """"type":"assistant_message","messageId":"message:$BODIES:reply","text":"","textOmitted":true,"streaming":false"""
            )
        )
        val progress = JsonObject(
            subagent() + mapOf(
                "progress" to JsonPrimitive("Checked the build."),
                "prompt" to JsonPrimitive(""),
                "promptOmitted" to JsonPrimitive(true),
                "result" to kotlinx.serialization.json.JsonNull,
                "resultOmitted" to JsonPrimitive(true)
            )
        )

        projection = projection.apply(V2EventPayload("message.updated", reply))
            .apply(V2EventPayload("turn-item.updated", replyItem))
            .apply(V2EventPayload("subagent.updated", progress))

        val restored = projection.turnItems.single { it.text("id") == "turn-item:$BODIES:reply" }
        assertEquals("Fixed the import, and the test.", restored.text("text"))
        assertFalse(restored.containsKey("textOmitted"))
        val record = projection.subagents.single()
        assertEquals("Find the failing import.", record.text("prompt"))
        assertEquals("It was src/index.ts.", record.text("result"))
        assertEquals("Checked the build.", record.text("progress"))
        assertFalse(record.containsKey("promptOmitted") || record.containsKey("resultOmitted"))
        assertEquals("Fixed the import, and the test.", projection.detail().messages.last().text)
    }

    @Test
    fun `live subagent items restore from the record and checkpoint items from the capture`() {
        var projection = V2Orchestration.snapshot(boundedSnapshot(bodiesProjection(), extra = "")).projection
        val task = JsonObject(
            bodiesItem(
                "task",
                2,
                """"type":"subagent","subagentId":"node:$BODIES:task","origin":"app_owned","driver":"codex",
                "providerInstanceId":"codex","childThreadId":null,"prompt":"","promptOmitted":true,"result":null,"resultOmitted":true"""
            )
        )
        val checkpoint = JsonObject(
            bodiesItem(
                "checkpoint",
                4,
                """"type":"checkpoint","checkpointId":"checkpoint:$BODIES","scopeId":"scope:$BODIES","files":[],"filesOmitted":true"""
            )
        )

        projection = projection.apply(V2EventPayload("turn-item.updated", task))
            .apply(V2EventPayload("turn-item.updated", checkpoint))

        val restoredTask = projection.turnItems.single { it.text("id") == "turn-item:$BODIES:task" }
        assertEquals("Find the failing import.", restoredTask.text("prompt"))
        assertEquals("It was src/index.ts.", restoredTask.text("result"))
        val restoredCheckpoint = projection.turnItems.single { it.text("id") == "turn-item:$BODIES:checkpoint" }
        assertEquals(json(FILES.trimIndent()).getValue("files"), restoredCheckpoint["files"])
        assertFalse(restoredCheckpoint.containsKey("filesOmitted"))
    }

    @Test
    fun `a marked body without its record stays blank and marked`() {
        val projection = V2Orchestration.snapshot(boundedSnapshot(bodiesProjection(), extra = "")).projection
        val orphan = JsonObject(
            bodiesItem(
                "orphan",
                6,
                """"type":"assistant_message","messageId":"message:unknown","text":"","textOmitted":true,"streaming":false"""
            )
        )
        val applied = projection.apply(V2EventPayload("turn-item.updated", orphan))
            .turnItems.single { it.text("id") == "turn-item:$BODIES:orphan" }
        assertEquals("", applied.text("text"))
        assertEquals("true", applied.text("textOmitted"))

        val stillMarked = JsonObject(
            subagent() + mapOf("prompt" to JsonPrimitive(""), "promptOmitted" to JsonPrimitive(true))
        )
        val item = JsonObject(
            bodiesItem(
                "task",
                2,
                """"type":"subagent","subagentId":"node:$BODIES:task","prompt":"","promptOmitted":true,"result":null"""
            )
        )
        val unresolved = V2WireCompaction.restoreTurnItemBodies(
            item,
            V2WireCompaction.BodySource({ null }, { null }, { stillMarked })
        )
        assertEquals("", unresolved.text("prompt"))
        assertEquals("true", unresolved.text("promptOmitted"))
    }

    @Test
    fun `cached projections round trip subagent records and older caches decode without them`() {
        val live = V2Orchestration.snapshot(boundedSnapshot(bodiesProjection(), extra = "")).projection
        val cached = T3Json.encodeToString(V2Projection.serializer(), live)
        assertEquals(live, T3Json.decodeFromString(V2Projection.serializer(), cached))
        val older = """{"thread":{"id":"$BODIES"},"updatedAt":"$AT"}"""
        assertEquals(emptyList<JsonObject>(), T3Json.decodeFromString(V2Projection.serializer(), older).subagents)
    }

    private fun boundedSnapshot(projection: String, extra: String) = json(
        """{"snapshotSequence":12,"projection":$projection,"historyCursor":null,"hasMoreHistory":false,"latestLocalTurnOrdinal":null$extra}"""
    )

    private fun bodiesProjection(): String {
        val items = bodiesItems()
        return projection(BODIES, turnItems = items, rows = items.map { local(BODIES, it) }, records = true)
    }

    /** What the server sends with `dedupeBodies` and `compactTurnItems`: bodies blanked, turn items omitted. */
    private fun compactBodiesProjection(): String {
        val deduped = bodiesItems().map { item ->
            when (item.text("type")) {
                "user_message", "assistant_message" -> JsonObject(
                    item + mapOf("text" to JsonPrimitive(""), "textOmitted" to JsonPrimitive(true))
                )
                "checkpoint" -> JsonObject(
                    item + mapOf("files" to JsonArray(emptyList()), "filesOmitted" to JsonPrimitive(true))
                )
                "subagent" -> JsonObject(
                    item + mapOf(
                        "prompt" to JsonPrimitive(""),
                        "promptOmitted" to JsonPrimitive(true),
                        "result" to kotlinx.serialization.json.JsonNull,
                        "resultOmitted" to JsonPrimitive(true)
                    )
                )
                else -> item
            }
        }
        return projection(BODIES, turnItems = emptyList(), rows = deduped.map { local(BODIES, it) }, records = true)
    }

    private fun bodiesItems(): List<JsonObject> = listOf(
        bodiesItem(
            "prompt",
            1,
            """"type":"user_message","messageId":"message:$BODIES_RUN","inputIntent":"turn_start","text":"Please fix the build.",
            "attachments":[],"createdBy":"user","creationSource":"web""""
        ),
        bodiesItem(
            "task",
            2,
            """"type":"subagent","subagentId":"node:$BODIES:task","origin":"app_owned","driver":"codex","providerInstanceId":"codex",
            "childThreadId":null,"prompt":"Find the failing import.","result":"It was src/index.ts.""""
        ),
        bodiesItem(
            "reply",
            3,
            """"type":"assistant_message","messageId":"message:$BODIES:reply","text":"Fixed the import.","streaming":false"""
        ),
        bodiesItem(
            "checkpoint",
            4,
            """"type":"checkpoint","checkpointId":"checkpoint:$BODIES","scopeId":"scope:$BODIES",${FILES.trim().removeSurrounding(
                "{",
                "}"
            )}"""
        )
    )

    private fun bodiesItem(key: String, ordinal: Int, fields: String): JsonObject = json(
        """{"id":"turn-item:$BODIES:$key","threadId":"$BODIES","runId":"$BODIES_RUN","nodeId":null,"providerThreadId":null,
        "providerTurnId":null,"nativeItemRef":null,"parentItemId":null,"ordinal":$ordinal,"status":"completed","title":null,
        "startedAt":"$AT","completedAt":"$AT","updatedAt":"$AT",$fields}"""
    )

    private fun message(key: String, role: String, text: String): JsonObject = json(
        """{"id":"message:${if (key == "prompt") BODIES_RUN else "$BODIES:$key"}","threadId":"$BODIES","runId":"$BODIES_RUN","nodeId":null,
        "role":"$role","text":"$text","attachments":[],"streaming":false,"createdBy":"${if (role == "user") "user" else "agent"}",
        "creationSource":"${if (role == "user") "web" else "provider"}","createdAt":"$AT","updatedAt":"$AT"}"""
    )

    private fun subagent(): JsonObject = json(
        """{"id":"node:$BODIES:task","threadId":"$BODIES","runId":"$BODIES_RUN","parentNodeId":"node:$BODIES_RUN","origin":"app_owned",
        "createdBy":"agent","driver":"codex","providerInstanceId":"codex","providerThreadId":null,"childThreadId":null,
        "nativeTaskRef":null,"prompt":"Find the failing import.","title":null,"model":null,"status":"completed",
        "result":"It was src/index.ts.","startedAt":"$AT","completedAt":"$AT","updatedAt":"$AT"}"""
    )

    private fun forkProjection(compact: Boolean): String {
        val parentItems = (1..3).map { tool(PARENT, "parent-$it", it, run = PARENT_RUN) }
        val marker = json(
            """{"id":"turn-item:$FORK:fork","threadId":"$FORK","runId":null,"type":"fork","ordinal":0,"status":"completed",
            "source":{"type":"run","threadId":"$PARENT","runId":"$PARENT_RUN"},"updatedAt":"$AT"}"""
        )
        val own = (1..4).map { tool(FORK, "fork-$it", it, run = null) }
        val rows =
            parentItems.map { row(it, "inherited", PARENT) } + row(marker, "synthetic", PARENT) +
                own.map { local(FORK, it) }
        return projection(FORK, turnItems = if (compact) emptyList() else own, rows = rows)
    }

    private fun tool(thread: String, key: String, ordinal: Int, run: String? = LONG_RUN): JsonObject = json(
        """{"id":"turn-item:$thread:$key","threadId":"$thread","runId":${run?.let {
            "\"$it\""
        } ?: "null"},"nodeId":null,
        "providerThreadId":null,"providerTurnId":null,"nativeItemRef":null,"parentItemId":null,"ordinal":$ordinal,
        "status":"completed","title":"$key","startedAt":"$AT","completedAt":"$AT","updatedAt":"$AT",
        "type":"command_execution","input":"echo $key","exitCode":0,"outputOmitted":true}"""
    )

    private fun local(thread: String, item: JsonObject) = row(item, "local", thread)

    private fun row(item: JsonObject, visibility: String, source: String) = json(
        """{"position":0,"visibility":"$visibility","sourceThreadId":"$source","sourceItemId":"${item.text(
            "id"
        )}","item":$item}"""
    )

    private fun projection(
        thread: String,
        turnItems: List<JsonObject>,
        rows: List<JsonObject>,
        records: Boolean = false
    ): String {
        val positioned = rows.mapIndexed { index, row -> JsonObject(row + ("position" to JsonPrimitive(index))) }
        val run = if (thread ==
            FORK
        ) {
            ""
        } else {
            """{"id":"${runOf(thread)}","threadId":"$thread","ordinal":1,"status":"completed",
            "requestedAt":"$AT","startedAt":"$AT","completedAt":"$AT","userMessageId":"message:${runOf(
                thread
            )}","rootNodeId":"node:${runOf(thread)}"}"""
        }
        return """{"thread":{"id":"$thread","projectId":"project:compact-transport","title":"$thread","providerInstanceId":"codex",
            "modelSelection":{"instanceId":"codex","model":"gpt-5.4"},"runtimeMode":"full-access","interactionMode":"default",
            "lineage":{"parentThreadId":null,"relationshipToParent":null,"rootThreadId":"$thread"},"createdAt":"$AT","updatedAt":"$AT"},
            "runs":[$run],"attempts":[],"nodes":[],
            "subagents":[${if (records) subagent() else ""}],
            "messages":[${if (records) {
            "${message(
                "prompt",
                "user",
                "Please fix the build."
            )},${message("reply", "assistant", "Fixed the import.")}"
        } else {
            ""
        }}],
            "checkpoints":[${if (records) checkpointRecord() else ""}],
            "turnItems":${JsonArray(turnItems)},"visibleTurnItems":${JsonArray(positioned)},"updatedAt":"$AT"}"""
    }

    private fun checkpointRecord() =
        """{"id":"checkpoint:$BODIES","threadId":"$BODIES","scopeId":"scope:$BODIES","runId":"$BODIES_RUN","nodeId":"node:$BODIES_RUN",
        "parentCheckpointId":null,"ordinalWithinScope":1,"appRunOrdinal":1,"ref":"refs/t3/$BODIES","status":"ready",
        ${FILES.trim().removeSurrounding("{", "}")},"capturedAt":"$AT"}"""

    private fun runOf(thread: String) = when (thread) {
        BODIES -> BODIES_RUN
        LONG_THREAD -> LONG_RUN
        else -> PARENT_RUN
    }

    /** Kotlin port of the server's `internRecordIds`, used to produce `compactIds` wire fixtures. */
    private fun internIds(value: JsonObject, container: String?): JsonObject {
        val ids = mutableListOf<JsonElement>()
        val index = mutableMapOf<JsonElement, Int>()
        fun ref(value: JsonElement): JsonElement {
            if (value !is JsonPrimitive ||
                value is kotlinx.serialization.json.JsonNull ||
                value.content in setOf("true", "false") &&
                !value.isString
            ) {
                return value
            }
            return JsonPrimitive(
                index.getOrPut(value) {
                    ids.add(value)
                    ids.size - 1
                }
            )
        }
        fun record(record: JsonObject): JsonObject = JsonObject(
            record.mapValues { (key, slot) ->
                when {
                    key == "id" || key.endsWith("Id") -> ref(slot)
                    key.endsWith("Ids") && slot is JsonArray -> JsonArray(slot.map(::ref))
                    key == "item" && slot is JsonObject -> record(slot)
                    else -> slot
                }
            }
        )
        fun records(target: JsonObject): JsonObject = JsonObject(
            target.mapValues { (_, slot) ->
                when (slot) {
                    is JsonArray -> JsonArray(slot.map { if (it is JsonObject) record(it) else it })
                    is JsonObject -> record(slot)
                    else -> slot
                }
            }
        )
        val interned = if (container ==
            null
        ) {
            records(value)
        } else {
            JsonObject(value + (container to records(value.getValue(container).jsonObject)))
        }
        return JsonObject(interned + ("ids" to JsonArray(ids)))
    }

    private fun JsonElement.isNumber() = this is JsonPrimitive && !isString && contentOrNull?.toDoubleOrNull() != null

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun json(value: String): JsonObject = T3Json.parseToJsonElement(value).jsonObject

    private companion object {
        const val AT = "2026-10-01T00:00:00.000Z"
        const val PARENT = "thread:compact-transport:parent"
        const val FORK = "thread:compact-transport:fork"
        const val LONG_THREAD = "thread:compact-transport:long"
        const val BODIES = "thread:compact-transport:bodies"
        const val PARENT_RUN = "run:compact-transport:parent"
        const val LONG_RUN = "run:compact-transport:long"
        const val BODIES_RUN = "run:compact-transport:bodies"
        const val FILES = """{"files":[{"path":"src/index.ts","kind":"modified","additions":4,"deletions":2}]}"""
    }
}
