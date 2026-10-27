package de.chennemann.agentic.ui.chat

import java.time.Instant

internal fun timelineInstant(timestamp: String): Instant = runCatching { Instant.parse(timestamp) }.getOrDefault(Instant.EPOCH)

/** Published pages remain in the conversation after ordinary work has folded away. */
internal fun publishHtmlRenders(items: List<TimedItem>): List<TimedItem> = items.map { entry ->
    val tool = (entry.item as? ChatTimelineItemUi.Activity)?.value as? ChatActivityUi.Tool
    val page = tool?.htmlRender
    if (page != null && tool.status == ActivityStatusUi.COMPLETED) {
        entry.copy(item = ChatTimelineItemUi.HtmlRender(tool.id, page))
    } else entry
}

internal fun collapseToolLifecycles(items: List<TimedItem>): List<TimedItem> {
    val result = mutableListOf<TimedItem>()
    val positions = mutableMapOf<String, Int>()
    for (item in items) {
        val tool = (item.item as? ChatTimelineItemUi.Activity)?.value as? ChatActivityUi.Tool
        val key = tool?.lifecycleKey
        val position = key?.let(positions::get)
        if (tool == null || position == null) {
            if (key != null) positions[key] = result.size
            result += item
        } else {
            val previousItem = result[position]
            val previous = (previousItem.item as ChatTimelineItemUi.Activity).value as ChatActivityUi.Tool
            val command = tool.command ?: previous.command
            val output = tool.output ?: previous.output
            val files = (previous.changedFiles + tool.changedFiles).distinct()
            val detail = listOfNotNull(command, output, files.takeIf { it.isNotEmpty() }?.joinToString("\n"))
                .distinct().joinToString("\n\n").ifBlank { tool.detail ?: previous.detail }
            result[position] = previousItem.copy(endedAt = item.endedAt, item = ChatTimelineItemUi.Activity(tool.copy(
                id = previous.id,
                subtitle = tool.subtitle ?: previous.subtitle,
                detail = detail,
                command = command,
                output = output,
                changedFiles = files,
                status = if (previous.status in setOf(ActivityStatusUi.COMPLETED, ActivityStatusUi.FAILED, ActivityStatusUi.STOPPED) &&
                    tool.status in setOf(ActivityStatusUi.PENDING, ActivityStatusUi.RUNNING)) previous.status else tool.status,
            )))
        }
    }
    return result
}

internal fun foldSettledTurns(
    items: List<TimedItem>,
    latestTurnId: String?,
    runningTurnId: String?,
    startedAt: String?,
    completedAt: String?,
    interrupted: Boolean,
    diffStats: Map<String, TurnDiffStatUi> = emptyMap(),
): List<ChatTimelineItemUi> {
    val hidden = mutableSetOf<String>()
    val summaries = mutableMapOf<String, ChatTimelineItemUi.WorkSummary>()
    val startBoundaries = mutableMapOf<String, String>()
    var pendingUserBoundary: String? = null
    for (entry in items) {
        if ((entry.item as? ChatTimelineItemUi.Message)?.value?.author == ChatMessageAuthorUi.USER) {
            pendingUserBoundary = entry.at
        } else if (entry.turnId != null && entry.turnId !in startBoundaries) {
            startBoundaries[entry.turnId] = pendingUserBoundary ?: entry.at
            pendingUserBoundary = null
        }
    }
    for ((turnId, entries) in items.filter { it.turnId != null }.groupBy { it.turnId }) {
        if (turnId == runningTurnId) continue
        val messages = entries.mapNotNull { (it.item as? ChatTimelineItemUi.Message)?.value }
        if (messages.any { it.isStreaming && it.author != ChatMessageAuthorUi.REASONING }) continue
        // Settled progress notes fold with the work; the turn's final answer stays.
        val keptMessages = setOfNotNull(messages.lastOrNull { it.author == ChatMessageAuthorUi.ASSISTANT }?.id)
        val folded = entries.filter { entry ->
            when (val item = entry.item) {
                is ChatTimelineItemUi.Message -> item.id !in keptMessages &&
                    item.value.author in setOf(ChatMessageAuthorUi.ASSISTANT, ChatMessageAuthorUi.REASONING)
                is ChatTimelineItemUi.Activity -> (item.value is ChatActivityUi.Tool && !item.value.isAgent) || (item.value is ChatActivityUi.Information && !item.value.keepVisible) ||
                    (item.value is ChatActivityUi.Unknown && item.value.typeLabel?.startsWith("user-input.") != true)
                else -> false
            }
        }
        if (folded.isEmpty()) continue
        if (folded.all { (it.item as? ChatTimelineItemUi.Message)?.value?.author == ChatMessageAuthorUi.REASONING }) continue
        val start = startedAt.takeIf { turnId == latestTurnId } ?: startBoundaries[turnId] ?: entries.first().at
        val end = completedAt.takeIf { turnId == latestTurnId } ?: entries.maxOf { entry ->
            timelineInstant((entry.item as? ChatTimelineItemUi.Message)?.value?.timestamp ?: entry.endedAt)
        }.toString()
        val duration = if (runCatching { Instant.parse(end) }.isSuccess) formatTurnDuration(start, end, 0) else null
        val stopped = turnId == latestTurnId && interrupted
        val label = if (stopped) duration?.let { "You stopped after $it" } ?: "You stopped this response"
            else duration?.let { "Worked for $it" } ?: "Worked"
        val failures = folded.count { ((it.item as? ChatTimelineItemUi.Activity)?.value as? ChatActivityUi.Tool)?.status == ActivityStatusUi.FAILED }
        summaries[folded.first().item.lazyKey] = ChatTimelineItemUi.WorkSummary(
            id = requireNotNull(turnId), label = label + if (failures > 0) " - $failures failed" else "", items = groupToolActivities(folded.map { entry ->
                val activity = (entry.item as? ChatTimelineItemUi.Activity)?.value
                if (activity is ChatActivityUi.Tool && activity.status in setOf(ActivityStatusUi.PENDING, ActivityStatusUi.RUNNING)) {
                    ChatTimelineItemUi.Activity(activity.copy(status = ActivityStatusUi.STOPPED))
                } else entry.item
            }),
            diffStat = diffStats[turnId],
        )
        hidden += folded.map { it.item.lazyKey }
    }
    return groupToolActivities(buildList {
        for (entry in items) {
            summaries[entry.item.lazyKey]?.let(::add)
            if (entry.item.lazyKey !in hidden) add(entry.item)
        }
    })
}
