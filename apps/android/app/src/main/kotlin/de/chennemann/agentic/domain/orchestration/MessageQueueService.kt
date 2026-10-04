package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.connection.ConnectionState
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.TurnMessageInput
import de.chennemann.agentic.t3.contract.UploadChatAttachment
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Live composer intents; scoped to an environment and thread, never projection messages. */
data class QueuedMessage(
    val environmentId: String,
    val command: ClientOrchestrationCommand.StartTurn,
    val queuedAfterSequence: Long,
    val paused: Boolean = false,
    val sendNow: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val id get() = command.commandId
    val threadId get() = command.threadId
}

class MessageQueueService(
    private val repository: OrchestrationRepository,
    private val environments: EnvironmentRepository,
    private val connection: ConnectionController,
    private val commands: CommandDispatcher,
    scope: CoroutineScope,
) {
    private val mutableMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val messages = mutableMessages.asStateFlow()
    private val dispatchSequences = mutableMapOf<Pair<String, String>, Long>()

    init {
        scope.launch {
            combine(messages, repository.shell, repository.clientConfig, environments.activeEnvironment, connection.state) {
                _, _, _, _, _ -> Unit
            }.collect { dispatchReady() }
        }
    }

    fun enqueue(
        environmentId: String,
        threadId: String,
        prompt: String,
        model: ModelSelection,
        runtimeMode: String,
        attachments: List<UploadChatAttachment>,
    ) {
        val command = ClientOrchestrationCommand.StartTurn(
            commandId = UUID.randomUUID().toString(),
            threadId = threadId,
            message = TurnMessageInput(
                messageId = UUID.randomUUID().toString(),
                text = prompt,
                attachments = attachments,
            ),
            modelSelection = model,
            runtimeMode = runtimeMode,
            interactionMode = "default",
            createdAt = Instant.now().toString(),
        )
        val queued = QueuedMessage(environmentId, command, repository.shell.value.sequence ?: -1)
        mutableMessages.update { it + queued }
    }

    fun pause(environmentId: String, threadId: String) {
        mutableMessages.update { messages ->
            messages.map {
                if (it.environmentId == environmentId && it.threadId == threadId) {
                    it.copy(paused = true, sendNow = false)
                } else it
            }
        }
    }

    fun sendNow(id: String) {
        mutableMessages.update { messages ->
            messages.map {
                if (it.id == id && !it.sending && it.error == null) {
                    it.copy(paused = false, sendNow = true)
                } else it
            }
        }
    }

    fun remove(id: String) {
        mutableMessages.update { messages -> messages.filterNot { it.id == id && !it.sending } }
    }

    private suspend fun dispatchReady() {
        val environmentId = environments.activeEnvironment.value?.id ?: return
        val candidates = messages.value.filter { it.environmentId == environmentId }
            .groupBy { it.threadId }.values.map { queue ->
                queue.firstOrNull { it.sendNow && !it.paused && it.error == null } ?: queue.first()
            }
        for (message in candidates) {
            // Read fresh projections after every suspending dispatch to another thread.
            val projection = repository.shell.value
            if (environments.activeEnvironment.value?.id != environmentId ||
                connection.state.value != ConnectionState.Live ||
                repository.clientConfig.value.value?.environment?.environmentId != environmentId ||
                projection.source != ProjectionSource.LIVE || !projection.synchronized
            ) return
            if (message.paused || message.sending || message.error != null) continue
            val thread = projection.value?.threads?.firstOrNull { it.id == message.threadId } ?: continue
            if (thread.archivedAt != null || thread.hasPendingApprovals || thread.hasPendingUserInput) continue
            val busy = thread.session?.status in ActiveStatuses || thread.latestTurn?.state in PendingTurnStates
            val sequence = projection.sequence ?: continue
            if (!message.sendNow && (busy || sequence <= message.queuedAfterSequence)) continue
            val key = environmentId to message.threadId
            if (sequence <= (dispatchSequences[key] ?: -1)) continue
            if (!claim(message)) continue
            try {
                val result = commands.dispatch(message.command)
                dispatchSequences[key] = result.sequence
                mutableMessages.update { messages -> messages.filterNot { it.id == message.id } }
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Exception) {
                // The durable outbox owns retries using this command ID. Pause the remaining
                // queue too, so dismissing the notice cannot overtake an unconfirmed send.
                mutableMessages.update { messages ->
                    messages.map {
                        when {
                            it.id == message.id -> it.copy(
                                sending = false,
                                paused = true,
                                sendNow = false,
                                error = "Send could not be confirmed. Check pending work to retry.",
                            )
                            it.environmentId == environmentId && it.threadId == message.threadId ->
                                it.copy(paused = true, sendNow = false)
                            else -> it
                        }
                    }
                }
            }
        }
    }

    private fun claim(message: QueuedMessage): Boolean {
        while (true) {
            val current = messages.value
            // Stop/remove may race with dispatch preparation. Only claim the exact ready intent.
            if (message !in current) return false
            val updated = current.map { if (it.id == message.id) it.copy(sending = true) else it }
            if (mutableMessages.compareAndSet(current, updated)) return true
        }
    }

    private companion object {
        val ActiveStatuses = setOf("starting", "running")
        val PendingTurnStates = setOf("pending", "running")
    }
}
