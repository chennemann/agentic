package de.chennemann.agentic.t3.contract

import kotlinx.serialization.Serializable

/**
 * Background work a thread's agent left running. Unknown kinds keep their name and are treated
 * like `background_task`, mirroring the server's decode fallback.
 */
@Serializable
data class OrchestrationPendingBackgroundTask(
    val taskId: String = "",
    val kind: String = "background_task",
    val description: String? = null,
    val awaited: Boolean = false
)

/**
 * Whether background work keeps the thread's agent waiting. Commands and monitors are as often a
 * dev server as a wait, so they hold only when the agent marks them awaited.
 */
fun List<OrchestrationPendingBackgroundTask>.holdsCompletion(): Boolean = any {
    it.awaited || (it.kind != "command" && it.kind != "monitor")
}
