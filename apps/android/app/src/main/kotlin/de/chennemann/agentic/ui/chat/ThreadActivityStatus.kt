package de.chennemann.agentic.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import de.chennemann.agentic.t3.contract.holdsCompletion
import java.time.Instant
import kotlinx.coroutines.delay

enum class ThreadActivityKind { WORKING, WAITING }

/**
 * Provider-neutral run state of a thread row: working on a turn, or waiting on another thread or on
 * background work the agent will resume from. [since] is when the shown elapsed time starts.
 */
data class ThreadActivityUi(
    val kind: ThreadActivityKind,
    val since: String? = null,
    /** False for a plan that is ready for review, which belongs back in the active list. */
    val inWorkingSection: Boolean = true,
    /** When work was last sent to the thread; the Working section lists the latest first. */
    val lastSentAt: String? = null,
)

private val ActiveRunStatuses = setOf("preparing", "queued", "starting", "running", "waiting")

/** Mirrors the T3 clients: pending requests take precedence, then thread waits, then run state. */
internal fun OrchestrationThreadShell.activity(): ThreadActivityUi? {
    if (hasPendingApprovals || hasPendingUserInput) return null
    val parked = pendingBackgroundTasks.holdsCompletion() && runStatus != "failed"
    val status = if (parked) null else activityRunStatus ?: runStatus
    val (kind, since) = when {
        status == "running" && blockedOnThreadWait -> ThreadActivityKind.WAITING to latestTurn?.completedAt
        status in ActiveRunStatuses -> ThreadActivityKind.WORKING to workingStartedAt()
        parked -> ThreadActivityKind.WAITING to latestTurn?.completedAt
        else -> return null
    }
    val planReady = interactionMode == "plan" && hasActionableProposedPlan && status !in ActiveRunStatuses
    return ThreadActivityUi(
        kind = kind,
        since = since,
        inWorkingSection = !planReady,
        lastSentAt = latestTurn?.requestedAt ?: latestUserMessageAt ?: createdAt,
    )
}

/** Timers count from the start of the work the active run owns, never from its last activity. */
private fun OrchestrationThreadShell.workingStartedAt(): String? {
    activityRunStartedAt?.let { return it }
    val turn = latestTurn ?: return null
    return if (turn.completedAt == null && turn.turnId == session?.activeTurnId) turn.startedAt ?: turn.requestedAt else null
}

/** Threads working or waiting on background work leave the active list for the Working section. */
internal fun List<ThreadPickerItemUi>.splitWorkingSection(): Pair<List<ThreadPickerItemUi>, List<ThreadPickerItemUi>> {
    val (working, active) = partition { it.activity?.inWorkingSection == true && !it.needsAttention }
    return active to working.sortedWith(
        compareByDescending<ThreadPickerItemUi> { it.activity?.lastSentAt?.epochMillis() ?: 0L }.thenBy { it.id },
    )
}

internal fun formatActivityElapsed(since: String?, nowMillis: Long): String? {
    val start = since?.epochMillis() ?: return null
    val seconds = ((nowMillis - start).coerceAtLeast(0L)) / 1_000L
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m"
    return "${minutes / 60}h ${minutes % 60}m"
}

internal fun ThreadActivityUi.label(nowMillis: Long): String {
    val name = when (kind) {
        ThreadActivityKind.WORKING -> "Working"
        ThreadActivityKind.WAITING -> "Waiting"
    }
    return formatActivityElapsed(since, nowMillis)?.let { "$name $it" } ?: name
}

/** Wall-clock milliseconds that advance every second while [ticking]. */
@Composable
internal fun rememberTickingNow(ticking: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) {
        while (ticking) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return now
}

private fun String.epochMillis(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
