package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.ServerConfigStreamEvent
import de.chennemann.agentic.t3.contract.ProviderInstance
import de.chennemann.agentic.t3.contract.ServerSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

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
        "usageLimitSourcesUpdated" -> event.payload.decodeOrNull<List<de.chennemann.agentic.t3.contract.UsageLimitSource>>("sources")
            ?.let { current.copy(usageLimitSources = it) } ?: current
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
        if (item is OrchestrationShellStreamItem.ArchiveSnapshot || item is OrchestrationShellStreamItem.ArchiveChange) {
            val value = current.value ?: OrchestrationShellSnapshot(emptyList(), emptyList(), 0)
            val sequence = item.sequence()
            if (item is OrchestrationShellStreamItem.ArchiveChange && sequence <= (value.archivedSequence ?: -1)) {
                return Reduction.Ignored(current)
            }
            val threads = when (item) {
                is OrchestrationShellStreamItem.ArchiveSnapshot ->
                    item.threads + value.threads.filter { it.archivedAt == null && it.id !in item.threads.map { thread -> thread.id } }
                is OrchestrationShellStreamItem.ArchiveChange -> {
                    val retained = value.threads.filterNot { it.id == item.threadId && it.archivedAt != null }
                    val thread = item.thread
                    if (thread == null) retained else retained.filterNot { it.id == item.threadId } + thread
                }
            }
            return Reduction.Applied(current.copy(value = value.copy(threads = threads, archivedSequence = sequence)))
        }
        if (item is OrchestrationShellStreamItem.Snapshot) {
            // Enrichment frames contain only repository identities, not a replacement shell.
            // Agentic does not project repositoryIdentity yet; never advance the resume cursor here.
            if (item.repositoryIdentityRefresh) return Reduction.Ignored(current)
            val incoming = item.snapshot.copy(
                threads = (item.snapshot.threads + current.value?.threads.orEmpty().filter { it.archivedAt != null }).distinctBy { it.id },
                archivedSequence = current.value?.archivedSequence,
            )
            return Reduction.Applied(snapshot(current, incoming, ProjectionSource.LIVE))
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
                threads = value.threads.filterNot { it.id == item.threadId && it.archivedAt == null },
            )

            is OrchestrationShellStreamItem.Snapshot,
            is OrchestrationShellStreamItem.ArchiveSnapshot,
            is OrchestrationShellStreamItem.ArchiveChange,
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
        val event = item as OrchestrationThreadStreamItem.Event
        val sequence = current.sequence ?: return Reduction.Gap(current, 0, event.sequence)
        if (event.sequence <= sequence) return Reduction.Ignored(current)
        val loaded = current.value ?: return Reduction.Gap(current, sequence + 1, event.sequence)
        if (event.event.type == "checkpoint.rollback-requested") {
            return Reduction.Gap(
                current.copy(historyGeneration = current.historyGeneration + 1, synchronized = false),
                sequence + 1,
                event.sequence,
            )
        }
        val updated = loaded.projection.apply(event.event)
        return Reduction.Applied(
            current.copy(
                value = if (event.event.type == "thread.deleted") null else loaded.copy(
                    snapshotSequence = event.sequence,
                    projection = updated,
                    thread = updated.detail(),
                ),
                sequence = event.sequence,
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
        val liveProjection = loaded.projection
        val incomingProjection = incoming.projection
        val liveIds = liveProjection.visibleTurnItems.map { it.item["id"] }.toSet()
        val merged = liveProjection.copy(
            visibleTurnItems = (
                incomingProjection.visibleTurnItems.filterNot { it.item["id"] in liveIds } + liveProjection.visibleTurnItems
            ).mapIndexed { index, row -> row.copy(position = index.toLong()) },
        )
        return current.copy(
            value = loaded.copy(
                projection = merged,
                thread = merged.detail(),
                page = page.copy(snapshotSequence = sequence),
            ),
        )
    }
}

private fun OrchestrationShellStreamItem.sequence(): Long = when (this) {
    is OrchestrationShellStreamItem.ArchiveSnapshot -> sequence
    is OrchestrationShellStreamItem.ArchiveChange -> sequence
    is OrchestrationShellStreamItem.ProjectUpserted -> sequence
    is OrchestrationShellStreamItem.ProjectRemoved -> sequence
    is OrchestrationShellStreamItem.ThreadUpserted -> sequence
    is OrchestrationShellStreamItem.ThreadRemoved -> sequence
    is OrchestrationShellStreamItem.Snapshot -> snapshot.snapshotSequence
    OrchestrationShellStreamItem.Synchronized -> error("Synchronization markers are unsequenced")
}

private inline fun <reified T> JsonObject.decodeOrNull(key: String? = null): T? = runCatching {
    T3Json.decodeFromJsonElement<T>(if (key == null) this else getValue(key))
}.getOrNull()

private fun <T> List<T>.replaceById(
    value: T,
    id: (T) -> String,
): List<T> = filterNot { id(it) == id(value) } + value
