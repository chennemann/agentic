package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.t3.contract.OrchestrationThreadShell

/** Resolve only sub-agent edges. Forks remain independent picker conversations. */
internal class ThreadLineage(threads: List<OrchestrationThreadShell>) {
    private val byId = threads.associateBy { it.id }
    private val owners = threads.associate { it.id to owner(it) }
    val conversations = threads.filter { owners[it.id] == it.id }
    val subagents = threads.filter { owners[it.id] != it.id }.groupBy { owners.getValue(it.id) }

    fun hasAttention(threadId: String): Boolean = subagents[threadId].orEmpty().any {
        it.hasPendingApprovals || it.hasPendingUserInput
    }

    private fun owner(thread: OrchestrationThreadShell): String {
        var current = thread
        val seen = mutableSetOf(thread.id)
        while (current.lineage?.relationshipToParent == "subagent") {
            val parent = byId[current.lineage?.parentThreadId] ?: return thread.id
            if (!seen.add(parent.id)) return thread.id
            current = parent
        }
        return current.id
    }
}

internal fun OrchestrationThreadShell.toPickerItem(needsAttention: Boolean = false) = ThreadPickerItemUi(
    id = id,
    title = title,
    supportingText = updatedAt,
    archived = archivedAt != null,
    active = session?.status in setOf("running", "starting"),
    snoozedUntil = snoozedUntil,
    needsAttention = needsAttention || hasPendingApprovals || hasPendingUserInput,
)
