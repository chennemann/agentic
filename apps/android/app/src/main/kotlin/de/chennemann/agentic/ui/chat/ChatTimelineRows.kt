package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.t3.contract.OrchestrationMessage
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Explains an approval that reached a full-access thread, or null for causes this client does not know. */
internal fun fullAccessOverrideExplanation(override: String?): String? = when (override) {
    "ask_rule" -> "Full access skips approvals, but an ask rule in the agent's own settings matches this action, " +
        "so it still asks. Remove or narrow that rule to stop these prompts."
    "provider_check" -> "Full access skips approvals, but the agent still stops for a few safety checks of its own " +
        "that no setting can skip, such as a delete whose path it can't verify. T3 Code can't turn these off, " +
        "so check the request and approve it if it's what you expect."
    else -> null
}

internal data class MessagePresentation(
    val text: String,
    val origin: ChatMessageOriginUi?,
)

private val LegacyAutomationPrefix = Regex("^\\[Triggered by schedule task: [^\\r\\n]+]\\r?\\n\\r?\\n")
private val LegacyAutomationMessageId = Regex("^scheduled-task-message:(.+):\\d+:(?:scheduled|manual)$")

/** Separates messages sent by automations, other agents, or T3 Code itself from the user's own. */
internal fun OrchestrationMessage.presentation(): MessagePresentation {
    if (role != "user" || context?.get("guest") is JsonObject) return MessagePresentation(text, null)
    if (scheduledTaskId != null) return MessagePresentation(text, ChatMessageOriginUi.AUTOMATION)
    val legacyPrefix = LegacyAutomationPrefix.find(text)
    if (legacyPrefix != null && (LegacyAutomationMessageId.matches(id) || createdBy == "agent")) {
        return MessagePresentation(text.substring(legacyPrefix.value.length), ChatMessageOriginUi.AUTOMATION)
    }
    val origin = when {
        createdBy != "agent" -> null
        creationSource == "server" -> ChatMessageOriginUi.T3CODE
        else -> ChatMessageOriginUi.AGENT
    }
    return MessagePresentation(text, origin)
}

/** One line that stands for a folded message. */
internal fun foldedMessageSummary(content: String): String = content.lineSequence()
    .map(String::trim)
    .firstOrNull(String::isNotEmpty)
    .orEmpty()

/** A prompt that only asks the provider to compact, so its turn is the compaction itself. */
internal fun OrchestrationMessage.isCompactionCommand(): Boolean =
    role == "user" && text.trim().lowercase() == "/compact" && attachments.isEmpty()

/** Why T3 Code sent this `/compact` on its own, or null for one someone asked for. */
internal fun OrchestrationMessage.idleCompactionReason(): String? {
    if (!isCompactionCommand()) return null
    idleCompaction?.let { return it }
    // Servers before `idleCompaction` marked their own as agent-created.
    return if (createdBy == "agent" && creationSource == "server") "beforeCacheExpires" else null
}

internal fun OrchestrationThreadDetail.isCompactionTurn(turnId: String): Boolean =
    messages.any { it.turnId == turnId && it.isCompactionCommand() }

private val IdleCompactingLabels = mapOf(
    "beforeCacheExpires" to "Compacting before the cache expires…",
    "onReturn" to "Compacting after being idle…",
)

private val IdleCompactionTitles = mapOf(
    "beforeCacheExpires" to "Context automatically compacted before cache expiry",
    "onReturn" to "Context compacted after being idle",
)

private const val ContextCompacted = "Context compacted"
private val ActiveItemStatuses = setOf("pending", "running", "waiting")

internal fun JsonObject.toCompactionUi(id: String): ChatActivityUi.Compaction {
    val status = text("status")
    val failed = status == "failed"
    val before = (get("beforeTokenCount") as? JsonPrimitive)?.longOrNull
    val after = (get("afterTokenCount") as? JsonPrimitive)?.longOrNull
    val label = when {
        failed -> "Context compaction failed"
        status == "cancelled" || status == "interrupted" -> "Context compaction stopped"
        status in ActiveItemStatuses -> "Compacting context"
        before != null && after != null -> "$ContextCompacted ${formatTokenCount(before)} → ${formatTokenCount(after)} tokens"
        else -> ContextCompacted
    }
    val failure = text("summary")?.trim()?.takeIf { failed && it.isNotEmpty() }
    return ChatActivityUi.Compaction(
        id = id,
        summary = failure?.let { "$label · $it" } ?: label,
        active = status in ActiveItemStatuses,
        failed = failed,
    )
}

/**
 * A bare `/compact` turn reads as one compaction row: the row stands in for the prompt until the
 * provider reports the compaction, and compactions T3 Code started say why.
 */
internal fun presentCompactions(
    items: List<TimedItem>,
    commandIdleReasons: Map<String, String?>,
    runningTurnId: String?,
): List<TimedItem> {
    val commandEntries = items.filter { entry ->
        entry.item is ChatTimelineItemUi.Message && entry.item.id in commandIdleReasons
    }
    if (commandEntries.isEmpty() && items.none { (it.item as? ChatTimelineItemUi.Activity)?.value is ChatActivityUi.Compaction }) {
        return items
    }
    val idleReasonByTurn = commandEntries.mapNotNull { entry ->
        val reason = commandIdleReasons[entry.item.id] ?: return@mapNotNull null
        entry.turnId?.let { it to reason }
    }.toMap()
    val compactedTurns = items.mapNotNullTo(mutableSetOf()) { entry ->
        entry.turnId?.takeIf { (entry.item as? ChatTimelineItemUi.Activity)?.value is ChatActivityUi.Compaction }
    }
    return items.mapNotNull { entry ->
        val item = entry.item
        val running = entry.turnId != null && entry.turnId == runningTurnId
        if (item is ChatTimelineItemUi.Message && item.id in commandIdleReasons) {
            if (entry.turnId != null && entry.turnId in compactedTurns) return@mapNotNull null
            val label = when {
                !running -> "Context compaction"
                else -> commandIdleReasons[item.id]?.let(IdleCompactingLabels::get) ?: "Compacting context"
            }
            return@mapNotNull entry.copy(
                item = ChatTimelineItemUi.Activity(ChatActivityUi.Compaction("compaction:${item.id}", label, active = running)),
            )
        }
        val compaction = (item as? ChatTimelineItemUi.Activity)?.value as? ChatActivityUi.Compaction
            ?: return@mapNotNull entry
        val active = compaction.active && running
        val reason = entry.turnId?.let(idleReasonByTurn::get)
        val label = when {
            compaction.failed -> compaction.summary
            active -> reason?.let(IdleCompactingLabels::get) ?: compaction.summary
            compaction.summary.startsWith(ContextCompacted) ->
                reason?.let(IdleCompactionTitles::get)?.let { it + compaction.summary.removePrefix(ContextCompacted) }
                    ?: compaction.summary
            else -> compaction.summary
        }
        entry.copy(item = ChatTimelineItemUi.Activity(compaction.copy(summary = label, active = active)))
    }
}

/** Each turn's diffstat from its latest ready checkpoint that changed files. */
internal fun List<JsonElement>.turnDiffStats(): Map<String, TurnDiffStatUi> = filterIsInstance<JsonObject>()
    .filter { it.text("status") == "ready" }
    .mapNotNull { checkpoint ->
        val turnId = checkpoint.text("turnId") ?: return@mapNotNull null
        val files = (checkpoint["files"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (files.isEmpty()) return@mapNotNull null
        turnId to TurnDiffStatUi(
            files = files.size,
            additions = files.sumOf { it.count("additions") },
            deletions = files.sumOf { it.count("deletions") },
        )
    }
    .toMap()

internal fun formatTokenCount(value: Long): String {
    val magnitude = kotlin.math.abs(value)
    val (divisor, suffix) = when {
        magnitude >= 1_000_000_000_000 -> 1e12 to "T"
        magnitude >= 1_000_000_000 -> 1e9 to "B"
        magnitude >= 1_000_000 -> 1e6 to "M"
        magnitude >= 1_000 -> 1e3 to "K"
        else -> return NumberFormat.getIntegerInstance(Locale.US).format(value)
    }
    val scaled = value / divisor
    val digits = when {
        kotlin.math.abs(scaled) >= 100 -> 0
        kotlin.math.abs(scaled) >= 10 -> 1
        else -> 2
    }
    return BigDecimal(scaled).setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + suffix
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.count(key: String): Int = (get(key) as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
