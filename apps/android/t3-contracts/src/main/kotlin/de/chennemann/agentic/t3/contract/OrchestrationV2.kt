package de.chennemann.agentic.t3.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Wire projection for orchestration protocol 2. Extensible provider payloads stay opaque. */
@Serializable
data class V2Projection(
    val thread: JsonObject,
    val runs: List<JsonObject> = emptyList(),
    val attempts: List<JsonObject> = emptyList(),
    val messages: List<JsonObject> = emptyList(),
    val turnItems: List<JsonObject> = emptyList(),
    val visibleTurnItems: List<V2VisibleItem> = emptyList(),
    val runtimeRequests: List<JsonObject> = emptyList(),
    val providerSessions: List<JsonObject> = emptyList(),
    val checkpoints: List<JsonObject> = emptyList(),
    val updatedAt: String,
    val latestLocalTurnOrdinal: Long? = null,
    val partialTimeline: Boolean = false
) {
    fun apply(event: V2EventPayload): V2Projection {
        return when (event.type) {
            "thread.created", "thread.archived", "thread.unarchived", "thread.deleted",
            "thread.settled", "thread.unsettled", "thread.snoozed", "thread.unsnoozed",
            "thread.metadata-updated", "thread.runtime-mode-updated", "thread.interaction-mode-updated",
            "thread.model-selection-updated", "thread.provider-switched", "thread.guest-share-updated"
            -> copy(thread = event.payload)
            "run.created", "run.updated" -> copy(runs = runs.upsert(event.payload))
            "run-attempt.created", "run-attempt.updated" -> copy(attempts = attempts.upsert(event.payload))
            "message.updated" -> copy(messages = messages.upsert(event.payload))
            "runtime-request.updated" -> copy(runtimeRequests = runtimeRequests.upsert(event.payload))
            "provider-session.attached", "provider-session.updated" -> copy(
                providerSessions = providerSessions.upsert(event.payload)
            )
            "provider-session.detached" -> copy(
                providerSessions = providerSessions.filterNot {
                    it.text("id") == event.payload.text("providerSessionId")
                }
            )
            "checkpoint.captured" -> copy(checkpoints = checkpoints.upsert(event.payload))
            "turn-item.updated" -> {
                val existing = visibleTurnItems.indexOfFirst { it.item.text("id") == event.payload.text("id") }
                val rows = if (existing >= 0) {
                    visibleTurnItems.toMutableList().apply { set(existing, get(existing).copy(item = event.payload)) }
                } else {
                    val ordinal = event.payload.number("ordinal")
                    val runId = event.payload.text("runId")
                    val local = visibleTurnItems.filter { it.visibility == "local" }
                    val unfinished = runs.any {
                        it.text("id") == runId && it.text("status") in ActiveRunStatuses + "queued"
                    }
                    val continued = local.any { it.item.text("runId") == runId && it.item.number("ordinal") <= ordinal }
                    val belowWindow =
                        ordinal <= (latestLocalTurnOrdinal ?: -1) ||
                            ordinal < (local.minOfOrNull { it.item.number("ordinal") } ?: -1)
                    if (partialTimeline && !unfinished && !continued && belowWindow) return this
                    val row = V2VisibleItem(
                        position = (visibleTurnItems.maxOfOrNull { it.position } ?: -1) + 1,
                        visibility = "local",
                        sourceThreadId = thread.requiredText("id"),
                        sourceItemId = event.payload.requiredText("id"),
                        item = event.payload
                    )
                    val insertion = visibleTurnItems.indexOfFirst {
                        it.visibility == "local" &&
                            it.item.number("ordinal") > ordinal
                    }
                    visibleTurnItems.toMutableList().apply { add(if (insertion < 0) size else insertion, row) }
                        .mapIndexed { index, value -> value.copy(position = index.toLong()) }
                }
                copy(turnItems = turnItems.upsert(event.payload), visibleTurnItems = rows)
            }
            else -> this
        }
    }

    fun detail(): OrchestrationThreadDetail {
        val base = T3Json.decodeFromJsonElement<OrchestrationThreadDetail>(thread)
        val latest = runs.maxByOrNull { it.number("ordinal") }
        val active = runs.filter { it.text("status") in ActiveRunStatuses }.maxByOrNull { it.number("ordinal") }
        val run = active ?: latest
        val status = run?.text("status") ?: "idle"
        val rows = visibleTurnItems.sortedBy { it.position }.map { it.item }
        val statuses = runs.associate { it.text("id") to it.text("status") }
        val displayed = rows.filterNot { item ->
            val status = statuses[item.text("runId")]
            status == "rolled_back" ||
                (
                    status == "cancelled" &&
                        item.text("type") == "user_message" &&
                        item.text("inputIntent") == "queued_turn"
                    ) ||
                (
                    item.text("type") == "run_interrupt_result" &&
                        attempts.any {
                            it.text("runId") == item.text("runId") &&
                                it.text("rootNodeId") == item.text("nodeId") &&
                                it.text("status") == "superseded"
                        } &&
                        turnItems.none {
                            it.text("type") == "run_interrupt_request" &&
                                it.text("runId") == item.text("runId")
                        }
                    )
        }
        val messageItems = displayed.filter { it.text("type") in MessageTypes }
        val timelineMessages = messageItems.map { item ->
            val type = item.requiredText("type")
            OrchestrationMessage(
                id = item.text("messageId") ?: item.requiredText("id"),
                turnId = item.text("runId"),
                role = when (type) {
                    "user_message" -> "user"
                    "reasoning" -> "reasoning"
                    else -> "assistant"
                },
                text = item.text("text").orEmpty(),
                streaming = item.bool("streaming"),
                createdAt = item.text("startedAt") ?: item.requiredText("updatedAt"),
                updatedAt = item.requiredText("updatedAt"),
                attachments = item["attachments"]?.let {
                    T3Json.decodeFromJsonElement<List<ChatAttachment>>(it)
                }.orEmpty(),
                context = item["context"] as? JsonObject
            )
        }
        val plans = displayed.filter { it.text("type") == "proposed_plan" }.map { item ->
            ProposedPlan(
                id = item.requiredText("planId"),
                turnId = item.text("runId") ?: item.requiredText("id"),
                planMarkdown = item.text("markdown").orEmpty(),
                implementedAt = runs.firstOrNull {
                    (it["sourcePlanRef"] as? JsonObject)?.text("planId") ==
                        item.text("planId")
                }
                    ?.text("requestedAt"),
                createdAt = item.text("startedAt") ?: item.requiredText("updatedAt"),
                updatedAt = item.requiredText("updatedAt")
            )
        }
        return base.copy(
            latestTurn = run?.latestTurn(),
            session = ThreadSession(
                threadId = base.id,
                status = sessionStatus(status),
                providerInstanceId = thread.text("providerInstanceId"),
                activeTurnId = active?.text("id"),
                runtimeMode = base.runtimeMode,
                updatedAt = updatedAt
            ),
            messages = timelineMessages,
            activities = displayed.filterNot { it.text("type") in MessageTypes || it.text("type") == "proposed_plan" }
                .map(::activity),
            proposedPlans = plans,
            checkpoints = checkpoints.map { checkpoint ->
                JsonObject(
                    checkpoint + mapOf(
                        "turnId" to JsonPrimitive(checkpoint.text("runId")),
                        "checkpointTurnCount" to (checkpoint["appRunOrdinal"] ?: JsonPrimitive(0))
                    )
                )
            }
        )
    }

    private fun activity(item: JsonObject): OrchestrationActivity {
        val type = item.requiredText("type")
        val request = runtimeRequests.firstOrNull { it.text("id") == item.text("requestId") }
        val pending = request?.text("status") == "pending" &&
            (request["responseCapability"] as? JsonObject)?.text("type") != "not_resumable"
        val kind = when (type) {
            "approval_request" -> if (pending) "approval.requested" else "approval.resolved"
            "user_input_request" -> if (pending) "user-input.requested" else "user-input.resolved"
            "command_execution", "dynamic_tool", "file_change", "file_search", "web_search", "todo_list" ->
                "tool.updated"
            "subagent" -> "task.updated"
            else -> type
        }
        val payload = buildJsonObject {
            item.filterValues { it != JsonNull }.forEach { (key, value) -> put(key, value) }
            put("itemId", item.requiredText("id"))
            if (type == "command_execution") put("command", item.text("input").orEmpty())
            item["output"]?.let { put("detail", if (it is JsonPrimitive) it else JsonPrimitive(it.toString())) }
            item.text("prompt")?.let { put("detail", it) }
            if (type == "subagent") {
                put("agentKind", "agent")
                put(
                    "detail",
                    item.text("result") ?: item.text("progress").orEmpty()
                )
            }
            request?.get("answers")?.let { put("answers", it) }
        }
        return OrchestrationActivity(
            id = item.requiredText("id"),
            turnId = item.text("runId"),
            sequence = item.number("ordinal"),
            kind = kind,
            tone = when {
                type == "error" || item.text("status") == "failed" -> "error"
                kind.startsWith("tool.") ||
                    kind.startsWith("task.") -> "tool"
                else -> "info"
            },
            summary = item.text("title") ?: item.text("message") ?: item.text("toolName") ?: type.replace('_', ' '),
            payload = payload,
            createdAt = item.text("startedAt") ?: item.requiredText("updatedAt")
        )
    }
}

@Serializable
data class V2VisibleItem(
    val position: Long,
    val visibility: String,
    val sourceThreadId: String,
    val sourceItemId: String,
    val item: JsonObject
)

@Serializable
data class V2EventPayload(val type: String, val payload: JsonObject = JsonObject(emptyMap()))

/** Decodes only the current wire protocol; normalized models are also used for the local cache. */
object V2Orchestration {
    fun archiveItem(value: JsonElement): OrchestrationShellStreamItem {
        val item = value.jsonObject
        return when (item.requiredText("kind")) {
            "snapshot" -> {
                val snapshot = item.getValue("snapshot").jsonObject
                OrchestrationShellStreamItem.ArchiveSnapshot(
                    snapshot.number("snapshotSequence"),
                    snapshot.objects("threads").map(::shellThread)
                )
            }
            "thread.updated" -> {
                val thread = shellThread(item.getValue("thread").jsonObject)
                OrchestrationShellStreamItem.ArchiveChange(item.number("sequence"), thread.id, thread)
            }
            "thread.removed" -> OrchestrationShellStreamItem.ArchiveChange(
                item.number("sequence"),
                item.requiredText("threadId")
            )
            else -> error("Unsupported T3 archive stream item")
        }
    }

    fun shellItem(value: JsonElement): OrchestrationShellStreamItem {
        val item = value.jsonObject
        return when (item.requiredText("kind")) {
            "snapshot" -> {
                val snapshot = item.getValue("snapshot").jsonObject
                val threads = snapshot.objects("threads") + snapshot.objects("archivedThreads")
                OrchestrationShellStreamItem.Snapshot(
                    OrchestrationShellSnapshot(
                        projects = T3Json.decodeFromJsonElement(snapshot.getValue("projects")),
                        threads = threads.distinctBy { it.text("id") }.map(::shellThread),
                        snapshotSequence = snapshot.number("snapshotSequence")
                    ),
                    repositoryIdentityRefresh = item.containsKey("resolvedRepositoryIdentityRoots")
                )
            }
            "thread.updated" -> OrchestrationShellStreamItem.ThreadUpserted(
                item.number("sequence"),
                shellThread(item.getValue("thread").jsonObject)
            )
            else -> T3Json.decodeFromJsonElement(value)
        }
    }

    fun shellThread(value: JsonObject): OrchestrationThreadShell {
        val base = T3Json.decodeFromJsonElement<OrchestrationThreadShell>(value)
        val status = value.text("status") ?: "idle"
        val request = value["pendingRuntimeRequest"] as? JsonObject
        return base.copy(
            latestTurn = value.text("latestRunId")?.let { id ->
                LatestTurn(
                    turnId = id,
                    state = turnStatus(status),
                    requestedAt =
                    value.text("latestRunRequestedAt") ?: base.updatedAt,
                    startedAt = value.text("latestRunStartedAt"),
                    completedAt = value.text("latestRunCompletedAt")
                )
            },
            session = ThreadSession(
                base.id,
                sessionStatus(status),
                providerInstanceId = value.text("providerInstanceId"),
                activeTurnId = value.text("activeRunId"),
                runtimeMode = base.runtimeMode,
                lastError = value.text("lastError"),
                updatedAt = base.updatedAt
            ),
            hasPendingApprovals = request != null && request.text("kind") != "user_input",
            hasPendingUserInput = request?.text("kind") == "user_input"
        )
    }

    fun snapshot(value: JsonObject): OrchestrationThreadDetailSnapshot {
        val projection = T3Json.decodeFromJsonElement<V2Projection>(value.getValue("projection")).copy(
            latestLocalTurnOrdinal = value.text("latestLocalTurnOrdinal")?.toLong(),
            partialTimeline = value.bool("hasMoreHistory")
        )
        val sequence = value.number("snapshotSequence")
        return OrchestrationThreadDetailSnapshot(
            sequence,
            projection.detail(),
            page = OrchestrationThreadDetailPage(value.text("historyCursor"), value.bool("hasMoreHistory"), sequence),
            projection = projection
        )
    }

    fun threadItem(value: JsonElement): OrchestrationThreadStreamItem {
        val item = value.jsonObject
        return when (item.requiredText("kind")) {
            "snapshot" -> OrchestrationThreadStreamItem.Snapshot(snapshot(item))
            "event" -> OrchestrationThreadStreamItem.Event(
                item.number("sequence"),
                T3Json.decodeFromJsonElement(item.getValue("event"))
            )
            "synchronized" -> OrchestrationThreadStreamItem.Synchronized
            else -> error("Unsupported T3 thread stream item")
        }
    }
}

private val ActiveRunStatuses = setOf("preparing", "starting", "running", "waiting")
private val MessageTypes = setOf("user_message", "assistant_message", "reasoning")
private fun turnStatus(status: String): String = when (status) {
    "preparing", "starting", "queued" -> "pending"
    "waiting" -> "running"
    else -> status
}
private fun sessionStatus(status: String): String = when (status) {
    "preparing", "starting" -> "starting"
    "running", "waiting" -> "running"
    else -> "ready"
}
private fun JsonObject.latestTurn(): LatestTurn = LatestTurn(
    requiredText("id"),
    turnStatus(requiredText("status")),
    requestedAt = requiredText("requestedAt"),
    startedAt = text("startedAt"),
    completedAt = text("completedAt")
)
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.requiredText(key: String): String = requireNotNull(text(key)) { "Missing T3 field: $key" }
private fun JsonObject.number(key: String): Long = (getValue(key) as JsonPrimitive).long
private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray).orEmpty().map { it.jsonObject }
private fun List<JsonObject>.upsert(value: JsonObject): List<JsonObject> =
    filterNot { it.text("id") == value.requiredText("id") } + value
