package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.LatestTurn
import de.chennemann.agentic.t3.contract.OrchestrationActivity
import de.chennemann.agentic.t3.contract.OrchestrationMessage
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetail
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.ProposedPlan
import de.chennemann.agentic.t3.contract.ThreadSession
import de.chennemann.agentic.t3.contract.ServerConfigStreamEvent
import de.chennemann.agentic.t3.contract.ProviderInstance
import de.chennemann.agentic.t3.contract.ServerSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

object ClientConfigReducer {
    fun applyEvent(
        current: de.chennemann.agentic.t3.contract.ServerConfig,
        event: ServerConfigStreamEvent,
    ): de.chennemann.agentic.t3.contract.ServerConfig = when (event.type) {
        "snapshot" -> event.config ?: current
        "providerStatuses" -> event.payload.decodeOrNull<List<ProviderInstance>>("providers")
            ?.let { current.copy(providers = it) } ?: current
        "settingsUpdated" -> event.payload.decodeOrNull<ServerSettings>("settings")
            ?.let { current.copy(settings = it) } ?: current
        else -> current
    }

    fun reduce(
        current: ProjectionState<de.chennemann.agentic.t3.contract.ServerConfig>,
        incoming: de.chennemann.agentic.t3.contract.ServerConfig,
        source: ProjectionSource,
    ): ProjectionState<de.chennemann.agentic.t3.contract.ServerConfig> {
        if (source == ProjectionSource.CACHE && current.source == ProjectionSource.LIVE) return current
        return ProjectionState(
            value = incoming,
            source = source,
            synchronized = source == ProjectionSource.LIVE,
        )
    }
}

object ShellProjectionReducer {
    fun snapshot(
        current: ProjectionState<OrchestrationShellSnapshot>,
        incoming: OrchestrationShellSnapshot,
        source: ProjectionSource,
    ): ProjectionState<OrchestrationShellSnapshot> {
        if (source == ProjectionSource.CACHE && current.source == ProjectionSource.LIVE) return current
        if (
            source == ProjectionSource.CACHE &&
            current.sequence != null &&
            incoming.snapshotSequence <= current.sequence
        ) {
            return current
        }
        return ProjectionState(
            value = incoming,
            sequence = incoming.snapshotSequence,
            source = source,
            synchronized = false,
        )
    }

    fun reduce(
        current: ProjectionState<OrchestrationShellSnapshot>,
        item: OrchestrationShellStreamItem,
    ): Reduction<ProjectionState<OrchestrationShellSnapshot>> {
        if (item is OrchestrationShellStreamItem.Snapshot) {
            return Reduction.Applied(snapshot(current, item.snapshot, ProjectionSource.LIVE))
        }
        if (item is OrchestrationShellStreamItem.Synchronized) {
            return Reduction.Applied(current.copy(synchronized = true, source = ProjectionSource.LIVE))
        }
        val sequence = item.sequence()
        val currentSequence = current.sequence
            ?: return Reduction.Gap(current, expected = 0, received = sequence)
        if (sequence <= currentSequence) return Reduction.Ignored(current)
        val value = current.value
            ?: return Reduction.Gap(current, expected = currentSequence + 1, received = sequence)
        val updated = when (item) {
            is OrchestrationShellStreamItem.ProjectUpserted -> value.copy(
                projects = value.projects.replaceById(item.project) { it.id },
            )

            is OrchestrationShellStreamItem.ProjectRemoved -> value.copy(
                projects = value.projects.filterNot { it.id == item.projectId },
                threads = value.threads.filterNot { it.projectId == item.projectId },
            )

            is OrchestrationShellStreamItem.ThreadUpserted -> value.copy(
                threads = value.threads.replaceById(item.thread) { it.id },
            )

            is OrchestrationShellStreamItem.ThreadRemoved -> value.copy(
                threads = value.threads.filterNot { it.id == item.threadId },
            )

            is OrchestrationShellStreamItem.Snapshot,
            OrchestrationShellStreamItem.Synchronized,
            -> value
        }
        return Reduction.Applied(
            current.copy(
                value = updated.copy(snapshotSequence = sequence),
                sequence = sequence,
                source = ProjectionSource.LIVE,
            ),
        )
    }
}

object ThreadProjectionReducer {
    fun snapshot(
        current: ProjectionState<OrchestrationThreadDetailSnapshot>,
        incoming: OrchestrationThreadDetailSnapshot,
        source: ProjectionSource,
    ): ProjectionState<OrchestrationThreadDetailSnapshot> {
        if (source == ProjectionSource.CACHE && current.source == ProjectionSource.LIVE) return current
        if (
            source == ProjectionSource.CACHE &&
            current.sequence != null &&
            incoming.snapshotSequence <= current.sequence
        ) {
            return current
        }
        return ProjectionState(
            value = incoming,
            sequence = incoming.snapshotSequence,
            source = source,
            synchronized = false,
            historyGeneration = current.historyGeneration + 1,
        )
    }

    fun reduce(
        current: ProjectionState<OrchestrationThreadDetailSnapshot>,
        item: OrchestrationThreadStreamItem,
    ): Reduction<ProjectionState<OrchestrationThreadDetailSnapshot>> {
        if (item is OrchestrationThreadStreamItem.Snapshot) {
            return Reduction.Applied(snapshot(current, item.snapshot, ProjectionSource.LIVE))
        }
        if (item is OrchestrationThreadStreamItem.Synchronized) {
            return Reduction.Applied(current.copy(synchronized = true, source = ProjectionSource.LIVE))
        }
        val event = (item as OrchestrationThreadStreamItem.Event).event
        val sequence = event.sequence
        val currentSequence = current.sequence
            ?: return Reduction.Gap(current, expected = 0, received = sequence)
        if (sequence <= currentSequence) return Reduction.Ignored(current)
        val snapshot = current.value
            ?: return Reduction.Gap(current, expected = currentSequence + 1, received = sequence)
        if (event.type == "thread.reverted") {
            // A new snapshot is authoritative after a history rewrite, including unloaded turns.
            return Reduction.Gap(
                current.copy(historyGeneration = current.historyGeneration + 1, synchronized = false),
                expected = currentSequence + 1,
                received = sequence,
            )
        }
        if (event.type == "thread.deleted") {
            return Reduction.Applied(
                current.copy(
                    value = null,
                    sequence = sequence,
                    source = ProjectionSource.LIVE,
                ),
            )
        }
        val thread = applyEvent(snapshot.thread, event.type, event.payload)
        return Reduction.Applied(
            current.copy(
                value = snapshot.copy(snapshotSequence = sequence, thread = thread),
                sequence = sequence,
                source = ProjectionSource.LIVE,
            ),
        )
    }

    fun mergeHistory(
        current: ProjectionState<OrchestrationThreadDetailSnapshot>,
        incoming: OrchestrationThreadDetailSnapshot,
        beforeCursor: String,
        historyGeneration: Long,
    ): ProjectionState<OrchestrationThreadDetailSnapshot>? {
        val loaded = current.value ?: return null
        val sequence = current.sequence ?: return null
        val page = incoming.page ?: return null
        if (current.historyGeneration != historyGeneration || loaded.thread.id != incoming.thread.id) return null
        if (loaded.page?.beforeCursor != beforeCursor || loaded.page?.hasMore != true) return null
        if (incoming.snapshotSequence < sequence || (page.threadSequence ?: 0) > sequence) return null
        val thread = loaded.thread.copy(
            messages = (incoming.thread.messages + loaded.thread.messages).associateBy { it.id }.values.toList(),
            activities = (incoming.thread.activities + loaded.thread.activities).associateBy { it.id }.values.toList(),
            proposedPlans = (incoming.thread.proposedPlans + loaded.thread.proposedPlans).associateBy { it.id }.values.toList(),
            checkpoints = (incoming.thread.checkpoints + loaded.thread.checkpoints).associateBy {
                (it as? JsonObject)?.get("turnId") ?: it
            }.values.toList(),
        )
        return current.copy(
            value = loaded.copy(thread = thread, page = page.copy(snapshotSequence = sequence)),
        )
    }

    private fun applyEvent(
        thread: OrchestrationThreadDetail,
        type: String,
        payload: JsonObject,
    ): OrchestrationThreadDetail = when (type) {
        "thread.meta-updated" -> thread.copy(
            title = payload.string("title") ?: thread.title,
            modelSelection = payload.decodeOrNull<ModelSelection>("modelSelection") ?: thread.modelSelection,
            updatedAt = payload.string("updatedAt") ?: thread.updatedAt,
        )

        "thread.runtime-mode-set" -> thread.copy(
            runtimeMode = payload.string("runtimeMode") ?: thread.runtimeMode,
            updatedAt = payload.string("updatedAt") ?: thread.updatedAt,
        )

        "thread.interaction-mode-set" -> thread.copy(
            interactionMode = payload.string("interactionMode") ?: thread.interactionMode,
            updatedAt = payload.string("updatedAt") ?: thread.updatedAt,
        )

        "thread.message-sent" -> payload.decodeOrNull<MessageEventPayload>()?.let { event ->
            val message = event.toMessage()
            val previous = thread.messages.firstOrNull { it.id == message.id }
            thread.copy(
                messages = thread.messages.replaceById(previous.merge(message)) { it.id },
                updatedAt = message.updatedAt,
            )
        } ?: thread

        "thread.session-set" -> thread.copy(
            session = payload.decodeOrNull<ThreadSession>("session"),
        )

        "thread.proposed-plan-upserted" -> payload.decodeOrNull<ProposedPlan>("proposedPlan")?.let { plan ->
            thread.copy(proposedPlans = thread.proposedPlans.replaceById(plan) { it.id })
        } ?: thread

        "thread.activity-appended" -> payload.decodeOrNull<OrchestrationActivity>("activity")?.let { activity ->
            thread.copy(activities = thread.activities.replaceById(activity) { it.id })
        } ?: thread

        "thread.turn-diff-completed" -> thread.applyTurnDiff(payload)

        "thread.archived" -> thread.copy(archivedAt = payload.string("archivedAt") ?: payload.string("updatedAt"))
        "thread.unarchived" -> thread.copy(archivedAt = null)
        "thread.snoozed" -> thread.copy(
            snoozedAt = payload.string("snoozedAt"),
            snoozedUntil = payload.string("snoozedUntil"),
            lastWakeReason = null,
            updatedAt = payload.string("updatedAt") ?: thread.updatedAt,
        )
        "thread.unsnoozed" -> thread.copy(
            snoozedAt = null,
            snoozedUntil = null,
            lastWakeReason = payload.string("reason"),
            updatedAt = payload.string("updatedAt") ?: thread.updatedAt,
        )
        "thread.approval-response-requested" -> thread.removeRequestActivity(
            "approval.requested",
            payload.string("requestId"),
        )

        "thread.user-input-response-requested" -> thread.removeRequestActivity(
            "user-input.requested",
            payload.string("requestId"),
        )

        else -> thread
    }
}

private fun OrchestrationThreadDetail.applyTurnDiff(payload: JsonObject): OrchestrationThreadDetail {
    val turnId = payload.string("turnId") ?: return this
    val completedAt = payload.string("completedAt") ?: updatedAt
    val currentTurn = latestTurn?.takeIf { it.turnId == turnId }
    return copy(
        checkpoints = checkpoints
            .filterNot { checkpoint ->
                (checkpoint as? JsonObject)?.string("turnId") == turnId
            } + payload,
        latestTurn = currentTurn?.copy(
            state = "completed",
            assistantMessageId = payload.string("assistantMessageId") ?: currentTurn.assistantMessageId,
            completedAt = completedAt,
        ) ?: LatestTurn(
            turnId = turnId,
            state = "completed",
            assistantMessageId = payload.string("assistantMessageId"),
            requestedAt = completedAt,
            completedAt = completedAt,
        ),
        updatedAt = completedAt,
    )
}

private fun OrchestrationShellStreamItem.sequence(): Long = when (this) {
    is OrchestrationShellStreamItem.ProjectUpserted -> sequence
    is OrchestrationShellStreamItem.ProjectRemoved -> sequence
    is OrchestrationShellStreamItem.ThreadUpserted -> sequence
    is OrchestrationShellStreamItem.ThreadRemoved -> sequence
    is OrchestrationShellStreamItem.Snapshot -> snapshot.snapshotSequence
    OrchestrationShellStreamItem.Synchronized -> error("Synchronization markers are unsequenced")
}

private fun OrchestrationThreadDetail.removeRequestActivity(
    kind: String,
    requestId: String?,
): OrchestrationThreadDetail {
    if (requestId == null) return this
    return copy(
        activities = activities.filterNot {
            it.kind == kind && it.payload.string("requestId") == requestId
        },
    )
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

private inline fun <reified T> JsonObject.decodeOrNull(key: String? = null): T? = runCatching {
    T3Json.decodeFromJsonElement<T>(if (key == null) this else getValue(key))
}.getOrNull()

private fun <T> List<T>.replaceById(
    value: T,
    id: (T) -> String,
): List<T> = filterNot { id(it) == id(value) } + value

private fun OrchestrationMessage?.merge(incoming: OrchestrationMessage): OrchestrationMessage {
    if (this == null || (!streaming && !incoming.streaming)) return incoming
    val mergedText = when {
        incoming.text.startsWith(text) -> incoming.text
        text.endsWith(incoming.text) -> text
        else -> text + incoming.text
    }
    return incoming.copy(text = mergedText)
}

@Serializable
private data class MessageEventPayload(
    val messageId: String,
    val turnId: String? = null,
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val attachments: List<de.chennemann.agentic.t3.contract.ChatAttachment> = emptyList(),
) {
    fun toMessage(): OrchestrationMessage = OrchestrationMessage(
        id = messageId,
        turnId = turnId,
        role = role,
        text = text,
        streaming = streaming,
        createdAt = createdAt,
        updatedAt = updatedAt,
        attachments = attachments,
    )
}
