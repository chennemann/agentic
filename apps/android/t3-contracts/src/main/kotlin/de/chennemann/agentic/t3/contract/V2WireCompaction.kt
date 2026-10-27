package de.chennemann.agentic.t3.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * Decodes the opt-in compact forms of orchestration protocol 2 (`compactIds`, `compactTurnItems`,
 * `dedupeBodies` and their `accept*` stream keys). Each rule mirrors the server exactly; a value
 * the client cannot restore stays as sent, as it does in the reference clients.
 */
object V2WireCompaction {
    /**
     * Resolves the response's `ids` table. On each record (an element of a container array, a
     * container object, or a record's `item`), numbers under `id` and `*Id` and numbers inside
     * `*Ids` arrays index the table. [container] names the key holding the records, or null when
     * they sit at the top level.
     */
    fun expandIds(value: JsonObject, container: String?): JsonObject {
        val ids = value["ids"] as? JsonArray ?: return value
        val rest = JsonObject(value - "ids")
        if (container == null) return expandContainer(rest, ids)
        val target = rest[container] as? JsonObject ?: return rest
        return JsonObject(rest + (container to expandContainer(target, ids)))
    }

    /** Expands, then restores omitted local turn items and deduplicated bodies of a snapshot. */
    fun snapshotProjection(snapshot: JsonObject): V2Projection {
        val expanded = expandIds(snapshot, "projection")
        val projection = T3Json.decodeFromJsonElement<V2Projection>(expanded.getValue("projection"))
        val omitted = (expanded["turnItemsOmitLocalVisible"] as? JsonPrimitive)?.booleanOrNull == true
        return (if (omitted) restoreLocalVisibleTurnItems(projection) else projection).let(::restoreSnapshotBodies)
    }

    /** Local visible rows lead the full `turnItems` list, so the server sent only the rest. */
    fun restoreLocalVisibleTurnItems(projection: V2Projection): V2Projection {
        val local = projection.visibleTurnItems.filter { projection.isLocalRow(it) }.map { it.item }
        if (local.isEmpty()) return projection
        return projection.copy(turnItems = local + projection.turnItems)
    }

    /** Inherited rows belong to another thread, whose records this projection lacks. */
    fun restoreSnapshotBodies(projection: V2Projection): V2Projection {
        val messages by lazy { projection.messages.associateBy({ it.text("id") }, { it["text"] }) }
        val checkpoints by lazy { projection.checkpoints.associateBy({ it.text("id") }, { it["files"] }) }
        val subagents by lazy { projection.subagents.associateBy { it.text("id") } }
        val source = BodySource(
            messageText = { messages[it] },
            checkpointFiles = { checkpoints[it] },
            subagent = { subagents[it] }
        )
        return projection.copy(
            visibleTurnItems = projection.visibleTurnItems.map { row ->
                if (!projection.isLocalRow(row)) row else row.copy(item = restoreTurnItemBodies(row.item, source))
            },
            turnItems = projection.turnItems.map { restoreTurnItemBodies(it, source) }
        )
    }

    /** A deduped live item repeats a record the stream sent earlier, which the projection holds. */
    fun restoreLiveBodies(projection: V2Projection, event: V2EventPayload): V2EventPayload = when (event.type) {
        "turn-item.updated" -> {
            val source = BodySource(
                messageText = { id -> projection.messages.lastOrNull { it.text("id") == id }?.get("text") },
                checkpointFiles = { id -> projection.checkpoints.lastOrNull { it.text("id") == id }?.get("files") },
                subagent = { id -> projection.subagents.lastOrNull { it.text("id") == id } }
            )
            event.copy(payload = restoreTurnItemBodies(event.payload, source))
        }
        "subagent.updated" -> event.copy(
            payload = restoreSubagentBodies(
                event.payload,
                projection.subagents.lastOrNull { it.text("id") == event.payload.text("id") }
            )
        )
        else -> event
    }

    internal fun restoreTurnItemBodies(item: JsonObject, source: BodySource): JsonObject = when (item.text("type")) {
        "user_message", "assistant_message" -> {
            val text = if (item.marked("textOmitted")) source.messageText(item.text("messageId")) else null
            if (text == null) item else JsonObject(item - "textOmitted" + ("text" to text))
        }
        "checkpoint" -> {
            val files = if (item.marked("filesOmitted")) source.checkpointFiles(item.text("checkpointId")) else null
            if (files == null) item else JsonObject(item - "filesOmitted" + ("files" to files))
        }
        "subagent" ->
            if (item.marked("promptOmitted") || item.marked("resultOmitted")) {
                restoreSubagentBodies(item, source.subagent(item.text("subagentId")))
            } else {
                item
            }
        else -> item
    }

    /** A marked body whose record is missing, or is itself still marked, stays blank and marked. */
    internal fun restoreSubagentBodies(value: JsonObject, previous: JsonObject?): JsonObject {
        if (previous == null) return value
        val prompt = value.marked("promptOmitted") && !previous.marked("promptOmitted")
        val result = value.marked("resultOmitted") && !previous.marked("resultOmitted")
        if (!prompt && !result) return value
        val restored = value.toMutableMap()
        if (prompt) restored.restoreFrom(previous, "prompt", "promptOmitted")
        if (result) restored.restoreFrom(previous, "result", "resultOmitted")
        return JsonObject(restored)
    }

    internal class BodySource(
        val messageText: (String?) -> JsonElement?,
        val checkpointFiles: (String?) -> JsonElement?,
        val subagent: (String?) -> JsonObject?
    )

    private fun MutableMap<String, JsonElement>.restoreFrom(previous: JsonObject, key: String, marker: String) {
        remove(marker)
        val body = previous[key]
        if (body == null) remove(key) else put(key, body)
    }

    private fun expandContainer(container: JsonObject, ids: JsonArray): JsonObject = JsonObject(
        container.mapValues { (_, value) ->
            when (value) {
                is JsonArray -> JsonArray(value.map { if (it is JsonObject) expandRecord(it, ids) else it })
                is JsonObject -> expandRecord(value, ids)
                else -> value
            }
        }
    )

    private fun expandRecord(record: JsonObject, ids: JsonArray): JsonObject = JsonObject(
        record.mapValues { (key, value) ->
            when {
                value.isNumber() -> if (isIdKey(key)) resolve(key, value as JsonPrimitive, ids) else value
                value is JsonArray ->
                    if (isIdListKey(key)) {
                        JsonArray(value.map { if (it.isNumber()) resolve(key, it as JsonPrimitive, ids) else it })
                    } else {
                        value
                    }
                key == "item" && value is JsonObject -> expandRecord(value, ids)
                else -> value
            }
        }
    )

    private fun resolve(key: String, index: JsonPrimitive, ids: JsonArray): JsonElement =
        index.intOrNull?.let(ids::getOrNull)
            ?: throw SerializationException("T3 ID table has no entry for $key")

    private fun isIdKey(key: String) = key == "id" || key.endsWith("Id")

    private fun isIdListKey(key: String) = key.endsWith("Ids")

    private fun JsonElement.isNumber(): Boolean =
        this is JsonPrimitive && this !is JsonNull && !isString && booleanOrNull == null

    private fun V2Projection.isLocalRow(row: V2VisibleItem): Boolean =
        row.visibility == "local" || row.sourceThreadId == thread.text("id")

    private fun JsonObject.marked(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
