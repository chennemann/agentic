package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.OrchestrationActivity
import de.chennemann.agentic.t3.contract.OrchestrationMessage
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetail
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.PortableJson
import de.chennemann.agentic.t3.contract.ProposedPlan
import de.chennemann.agentic.t3.contract.ThreadSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

object ClientConfigReducer {
    fun reduce(
        current: ProjectionState<de.chennemann.agentic.t3.contract.EnvironmentClientConfig>,
        incoming: de.chennemann.agentic.t3.contract.EnvironmentClientConfig,
        source: ProjectionSource,
    ): ProjectionState<de.chennemann.agentic.t3.contract.EnvironmentClientConfig> {
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

        "thread.message-sent" -> payload.decodeOrNull<MessageEventPayload>()?.let { message ->
            thread.copy(
                messages = thread.messages.replaceById(message.toMessage()) { it.id },
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

        "thread.archived" -> thread.copy(archivedAt = payload.string("archivedAt") ?: payload.string("updatedAt"))
        "thread.unarchived" -> thread.copy(archivedAt = null)
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
    PortableJson.decodeFromJsonElement<T>(if (key == null) this else getValue(key))
}.getOrNull()

private fun <T> List<T>.replaceById(
    value: T,
    id: (T) -> String,
): List<T> = filterNot { id(it) == id(value) } + value

@Serializable
private data class MessageEventPayload(
    val messageId: String,
    val turnId: String? = null,
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val attachments: List<kotlinx.serialization.json.JsonElement> = emptyList(),
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
