package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.t3.contract.LatestTurn
import de.chennemann.agentic.t3.contract.OrchestrationPendingBackgroundTask
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import de.chennemann.agentic.t3.contract.ThreadSession
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThreadActivityStatusTest {
    @Test
    fun `running thread works from the activity run start`() {
        val activity = thread(runStatus = "running", activityRunStartedAt = "2026-10-24T10:00:00Z").activity()
        assertEquals(ThreadActivityKind.WORKING, activity?.kind)
        assertEquals("2026-10-24T10:00:00Z", activity?.since)
    }

    @Test
    fun `activity run status outranks the latest run status`() {
        val activity = thread(runStatus = "completed", activityRunStatus = "waiting").activity()
        assertEquals(ThreadActivityKind.WORKING, activity?.kind)
    }

    @Test
    fun `without an activity start the timer falls back to the unfinished active run`() {
        val activity = thread(runStatus = "running").activity()
        assertEquals("2026-10-24T09:59:00Z", activity?.since)
    }

    @Test
    fun `a turn waiting on another thread shows Waiting`() {
        val activity = thread(runStatus = "running", blockedOnThreadWait = true).activity()
        assertEquals(ThreadActivityKind.WAITING, activity?.kind)
    }

    @Test
    fun `background work the agent waits on parks the thread as Waiting since the run ended`() {
        val activity = thread(
            runStatus = "completed",
            completedAt = "2026-10-24T10:05:00Z",
            tasks = listOf(OrchestrationPendingBackgroundTask("t", "subagent")),
        ).activity()
        assertEquals(ThreadActivityKind.WAITING, activity?.kind)
        assertEquals("2026-10-24T10:05:00Z", activity?.since)
    }

    @Test
    fun `a dev server the agent left running does not hold the thread`() {
        val shell = thread(runStatus = "completed", tasks = listOf(OrchestrationPendingBackgroundTask("dev", "command")))
        assertNull(shell.activity())
    }

    @Test
    fun `failed runs and pending requests never count as working`() {
        assertNull(thread(runStatus = "failed", tasks = listOf(OrchestrationPendingBackgroundTask("t", "subagent"))).activity())
        assertNull(thread(runStatus = "running").copy(hasPendingApprovals = true).activity())
        assertNull(thread(runStatus = "running").copy(hasPendingUserInput = true).activity())
    }

    @Test
    fun `a plan ready for review stays in the active list`() {
        val activity = thread(runStatus = "completed", tasks = listOf(OrchestrationPendingBackgroundTask("t", "subagent")))
            .copy(interactionMode = "plan", hasActionableProposedPlan = true)
            .activity()
        assertFalse(activity!!.inWorkingSection)
    }

    @Test
    fun `working section lists the thread work was last sent to first and keeps attention in the active list`() {
        val older = ThreadPickerItemUi("older", "Older", activity = ThreadActivityUi(ThreadActivityKind.WORKING, lastSentAt = "2026-10-24T09:00:00Z"))
        val newer = ThreadPickerItemUi("newer", "Newer", activity = ThreadActivityUi(ThreadActivityKind.WAITING, lastSentAt = "2026-10-24T10:00:00Z"))
        val attention = ThreadPickerItemUi("attention", "Attention", needsAttention = true, activity = ThreadActivityUi(ThreadActivityKind.WORKING))
        val idle = ThreadPickerItemUi("idle", "Idle")
        val (active, working) = listOf(older, idle, attention, newer).splitWorkingSection()
        assertEquals(listOf("idle", "attention"), active.map { it.id })
        assertEquals(listOf("newer", "older"), working.map { it.id })
    }

    @Test
    fun `elapsed labels match the T3 clients`() {
        val start = "2026-10-24T10:00:00Z"
        val startMillis = Instant.parse(start).toEpochMilli()
        assertEquals("Working 42s", ThreadActivityUi(ThreadActivityKind.WORKING, start).label(startMillis + 42_000))
        assertEquals("Waiting 5m", ThreadActivityUi(ThreadActivityKind.WAITING, start).label(startMillis + 5 * 60_000 + 59_000))
        assertEquals("Working 2h 3m", ThreadActivityUi(ThreadActivityKind.WORKING, start).label(startMillis + (123 * 60_000)))
        assertEquals("Waiting", ThreadActivityUi(ThreadActivityKind.WAITING, null).label(startMillis))
    }

    private fun thread(
        runStatus: String,
        activityRunStatus: String? = null,
        activityRunStartedAt: String? = null,
        blockedOnThreadWait: Boolean = false,
        completedAt: String? = null,
        tasks: List<OrchestrationPendingBackgroundTask> = emptyList(),
    ) = OrchestrationThreadShell(
        id = "thread",
        projectId = "project",
        title = "Thread",
        createdAt = "2026-10-24T09:00:00Z",
        updatedAt = "2026-10-24T10:00:00Z",
        latestTurn = LatestTurn("run-1", runStatus, requestedAt = "2026-10-24T09:58:00Z", startedAt = "2026-10-24T09:59:00Z", completedAt = completedAt),
        session = ThreadSession("thread", "running", activeTurnId = "run-1", updatedAt = "2026-10-24T10:00:00Z"),
        runStatus = runStatus,
        activityRunStatus = activityRunStatus,
        activityRunStartedAt = activityRunStartedAt,
        blockedOnThreadWait = blockedOnThreadWait,
        pendingBackgroundTasks = tasks,
    )
}
