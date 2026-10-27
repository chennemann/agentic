package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.domain.composer.string
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.ThreadQueueActions
import de.chennemann.agentic.domain.orchestration.ThreadQueueState
import de.chennemann.agentic.domain.orchestration.queueState
import de.chennemann.agentic.domain.orchestration.queuedRunEditBlockedReason
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetail
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An open edit of one queued message; it belongs to its thread, not to the screen. */
internal data class QueuedEditSession(
    val environmentId: String,
    val threadId: String,
    val runId: String,
    val original: String,
    val saving: Boolean = false,
    val error: String? = null,
)

internal data class QueueInteraction(val edit: QueuedEditSession? = null, val busyId: String? = null)

/**
 * Edits and manages the server queue of the focused thread. An edit ends once its message leaves
 * the queue, but never while its save is still in flight: the save keeps its claim until it settles.
 */
internal class ThreadQueueController(
    private val scope: CoroutineScope,
    private val environments: EnvironmentRepository,
    private val repository: OrchestrationRepository,
    private val queue: ThreadQueueActions?,
    private val onError: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(QueueInteraction())
    val state: StateFlow<QueueInteraction> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(environments.activeEnvironment, repository.focusedThread) { environment, thread ->
                environment?.id to thread
            }.collect { (environmentId, thread) -> endFinishedEdit(environmentId, thread) }
        }
    }

    fun requestEdit(runId: String) {
        val target = focused() ?: return
        if (queuedRunEditBlockedReason(target.detail.hasOpenQuestion()) != null) return
        if (mutableState.value.edit?.saving == true) return
        val run = target.queue.queuedRuns.firstOrNull { it.runId == runId && !it.guest } ?: return
        mutableState.update {
            it.copy(edit = QueuedEditSession(target.environmentId, target.detail.id, runId, run.text))
        }
    }

    fun cancelEdit() {
        mutableState.update { state -> if (state.edit?.saving == true) state else state.copy(edit = null) }
    }

    fun saveEdit(value: String) {
        val actions = queue ?: return
        val edit = mutableState.value.edit?.takeUnless { it.saving } ?: return
        val text = value.trim()
        if (text.isEmpty()) {
            mutableState.update { it.copy(edit = edit.copy(error = "A queued message cannot be empty.")) }
            return
        }
        if (text == edit.original.trim()) {
            mutableState.update { it.copy(edit = null) }
            return
        }
        val saving = edit.copy(saving = true, error = null)
        mutableState.update { it.copy(edit = saving) }
        scope.launch {
            try {
                actions.edit(edit.threadId, edit.runId, text)
                mutableState.update { state -> if (state.edit == saving) state.copy(edit = null) else state }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                val message = cause.message ?: "The queued message could not be saved."
                mutableState.update { state ->
                    if (state.edit == saving) state.copy(edit = saving.copy(saving = false, error = message)) else state
                }
                // The run may have started while the save was out; that ends the edit now.
                endFinishedEdit(environments.activeEnvironment.value?.id, repository.focusedThread.value)
            }
        }
    }

    /** Steers the running turn with a queued message, or starts it while the thread is stopped. */
    fun send(runId: String) {
        val target = focused() ?: return
        val queue = target.queue
        if (queue.queuedRuns.none { it.runId == runId } || mutableState.value.edit?.runId == runId) return
        val activeRunId = queue.activeRunId
        when {
            queue.canSendNow -> act(runId) { sendNow(target.detail.id, runId) }
            queue.canPromoteToSteer && activeRunId != null -> act(runId) {
                promoteToSteer(target.detail.id, runId, activeRunId)
            }
        }
    }

    fun remove(runId: String) {
        val target = focused() ?: return
        val edit = mutableState.value.edit
        if (edit?.runId == runId && edit.saving) return
        act(runId) {
            cancel(target.detail.id, runId)
            mutableState.update { state -> if (state.edit?.runId == runId) state.copy(edit = null) else state }
        }
    }

    fun resume() {
        val target = focused() ?: return
        if (!target.queue.held) return
        act(ResumeBusyId) { resume(target.detail.id) }
    }

    private fun act(id: String, block: suspend ThreadQueueActions.() -> Unit) {
        val actions = queue ?: return
        if (mutableState.value.busyId != null) return
        mutableState.update { it.copy(busyId = id) }
        scope.launch {
            try {
                actions.block()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                onError(cause.message ?: "The queue could not be updated.")
            } finally {
                mutableState.update { it.copy(busyId = null) }
            }
        }
    }

    private fun endFinishedEdit(environmentId: String?, thread: ProjectionState<OrchestrationThreadDetailSnapshot>) {
        val edit = mutableState.value.edit ?: return
        if (edit.saving || edit.environmentId != environmentId) return
        val snapshot = thread.value?.takeIf { it.thread.id == edit.threadId } ?: return
        if (snapshot.projection.queueState().queuedRuns.none { it.runId == edit.runId }) {
            mutableState.update { state -> if (state.edit == edit) state.copy(edit = null) else state }
        }
    }

    private fun focused(): FocusedQueue? {
        val environmentId = environments.activeEnvironment.value?.id ?: return null
        val threadId = repository.focusedThreadId.value ?: return null
        val snapshot = repository.focusedThread.value.value?.takeIf { it.thread.id == threadId } ?: return null
        return FocusedQueue(environmentId, snapshot.thread, snapshot.projection.queueState())
    }

    private data class FocusedQueue(
        val environmentId: String,
        val detail: OrchestrationThreadDetail,
        val queue: ThreadQueueState,
    )

    private companion object {
        const val ResumeBusyId = "queue:resume"
    }
}

/** Pending approvals that a redirecting send declines; question-like requests are not among them. */
internal fun OrchestrationThreadDetail.pendingApprovalRequestIds(): List<String> {
    val closed = activities.filter { it.kind == "approval.resolved" }.mapNotNull { it.payload.string("requestId") }.toSet()
    return activities.filter {
        it.kind == "approval.requested" &&
            it.payload.string("requestType") !in setOf("tool_user_input", "auth_tokens_refresh")
    }.mapNotNull { it.payload.string("requestId") }.filterNot { it in closed }.distinct()
}

internal fun OrchestrationThreadDetail.hasOpenQuestion(): Boolean {
    val closed = activities.filter { it.kind == "user-input.resolved" }.mapNotNull { it.payload.string("requestId") }.toSet()
    return activities.any { it.kind == "user-input.requested" && it.payload.string("requestId") !in closed }
}

/** Projects the server queue and pending approvals of the shown thread into the composer. */
internal fun ComposerUiState.withThreadQueue(snapshot: OrchestrationThreadDetailSnapshot?): ComposerUiState {
    val detail = snapshot?.thread ?: return this
    val queue = snapshot.projection.queueState()
    val openQuestion = detail.hasOpenQuestion()
    return copy(
        queuedMessages = queue.queuedRuns.map { QueuedMessageUi(it.runId, it.text, it.attachmentCount, !it.guest) },
        queueHeld = queue.held,
        queuedSendAction = when {
            queue.canSendNow -> QueuedSendActionUi.SEND_NOW
            queue.canPromoteToSteer -> QueuedSendActionUi.STEER
            else -> null
        },
        queuedEditBlockedReason = queuedRunEditBlockedReason(openQuestion),
        followUpQueueOnly = queue.activeRunId != null && !queue.canPromoteToSteer,
        approvalPending = !openQuestion && detail.pendingApprovalRequestIds().isNotEmpty(),
    )
}

/** Adds the queue edit and the queue action in flight; an edit shows only on its own thread. */
internal fun ComposerUiState.withQueueInteraction(
    interaction: QueueInteraction,
    environmentId: String?,
    threadId: String?,
): ComposerUiState {
    val edit = interaction.edit?.takeIf { it.environmentId == environmentId && it.threadId == threadId }
    return copy(
        queuedBusyId = interaction.busyId,
        queuedEdit = edit?.let { QueuedMessageEditUi(it.runId, it.original, it.saving, it.error) },
    )
}
