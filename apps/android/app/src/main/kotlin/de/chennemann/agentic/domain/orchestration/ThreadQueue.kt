package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.TurnMessageInput
import de.chennemann.agentic.t3.contract.UploadChatAttachment
import de.chennemann.agentic.t3.contract.V2Projection
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A user message waiting in the server queue of a thread. */
data class QueuedRun(
    val runId: String,
    val text: String,
    val attachmentCount: Int,
    /** Guest messages keep the guest's words; they can be cancelled but not edited. */
    val guest: Boolean,
)

/** Server-owned queue of a thread, derived from its live projection. */
data class ThreadQueueState(
    val activeRunId: String? = null,
    val activeRunStatus: String? = null,
    val queuedRuns: List<QueuedRun> = emptyList(),
    /** Stop holds the queue until the user resumes it or sends a selected message. */
    val held: Boolean = false,
    /** Nothing runs, so a selected queued message can start right away. */
    val canSendNow: Boolean = false,
    /** The running turn takes steering input, from a queued message or a follow-up. */
    val canPromoteToSteer: Boolean = false,
)

fun V2Projection.queueState(): ThreadQueueState {
    val active = runs.filter { it.text("status") in ActiveRunStatuses }.maxByOrNull { it.long("ordinal") ?: 0 }
    val automatic = messages.filter { it.present("delegatedCompletion") || it.present("notification") }
        .mapNotNull { it.text("id") }
        .toSet()
    val queued = runs.filter { it.text("status") == "queued" && it.text("userMessageId") !in automatic }
        .sortedWith(
            compareBy<JsonObject>({ it.long("queuePosition") ?: it.long("ordinal") ?: 0 }, { it.long("ordinal") ?: 0 }),
        )
    val capabilities = providerSessions.lastOrNull { it.text("status") !in setOf("stopped", "error") }
        ?.obj("capabilities")
        ?.obj("turns")
    // Without capability evidence the server still resolves steering, so do not hide it.
    val steerable = capabilities == null ||
        capabilities.bool("supportsActiveSteering") ||
        capabilities.bool("supportsSteeringByInterruptRestart")
    return ThreadQueueState(
        activeRunId = active?.text("id"),
        activeRunStatus = active?.text("status"),
        queuedRuns = queued.mapNotNull { run ->
            val id = run.text("id") ?: return@mapNotNull null
            val message = messages.firstOrNull { it.text("id") == run.text("userMessageId") }
            val guest = message?.obj("context")?.obj("guest")
            QueuedRun(
                runId = id,
                text = guest?.text("text") ?: message?.text("text") ?: "Queued message",
                attachmentCount = (message?.get("attachments") as? JsonArray)?.size ?: 0,
                guest = guest != null,
            )
        },
        held = queued.any { it.bool("queueHeld") },
        canSendNow = active == null && thread.text("archivedAt") == null && thread.text("deletedAt") == null,
        canPromoteToSteer = active?.text("status") == "running" && active.text("activeAttemptId") != null && steerable,
    )
}

/** Why queued messages cannot be edited right now, or null when they can. */
fun queuedRunEditBlockedReason(openQuestion: Boolean): String? =
    if (openQuestion) "Answer the question to edit queued messages" else null

interface ThreadQueueActions {
    suspend fun enqueue(
        threadId: String,
        prompt: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
        attachments: List<UploadChatAttachment> = emptyList(),
        context: JsonObject? = null,
    ): DispatchResult

    suspend fun cancel(threadId: String, runId: String): DispatchResult

    /** Starts one queued message while the thread is stopped. */
    suspend fun sendNow(threadId: String, runId: String): DispatchResult

    suspend fun resume(threadId: String): DispatchResult

    suspend fun promoteToSteer(threadId: String, runId: String, targetRunId: String): DispatchResult

    suspend fun edit(threadId: String, runId: String, text: String): DispatchResult
}

/**
 * Queued messages travel through the durable outbox like every send. Managing the queue acts on
 * what is queued right now, so those commands go out live: a replay after the run started would
 * only be rejected and hold up the outbox.
 */
class ThreadQueueService(
    private val commands: CommandDispatcher,
    private val live: CommandDispatcher = commands,
) : ThreadQueueActions {
    override suspend fun enqueue(
        threadId: String,
        prompt: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
        attachments: List<UploadChatAttachment>,
        context: JsonObject?,
    ): DispatchResult = commands.dispatch(
        ClientOrchestrationCommand.StartTurn(
            commandId = uuid(),
            threadId = threadId,
            message = TurnMessageInput(
                messageId = uuid(),
                text = prompt,
                attachments = attachments,
                context = context,
            ),
            modelSelection = modelSelection,
            interactionMode = "default",
            runtimeMode = runtimeMode,
            queue = true,
        ),
    )

    override suspend fun cancel(threadId: String, runId: String): DispatchResult =
        live.dispatch(ClientOrchestrationCommand.CancelQueuedRun(uuid(), threadId, runId))

    override suspend fun sendNow(threadId: String, runId: String): DispatchResult =
        live.dispatch(ClientOrchestrationCommand.StartQueuedRun(uuid(), threadId, runId))

    override suspend fun resume(threadId: String): DispatchResult =
        live.dispatch(ClientOrchestrationCommand.ResumeQueue(uuid(), threadId))

    override suspend fun promoteToSteer(threadId: String, runId: String, targetRunId: String): DispatchResult =
        live.dispatch(ClientOrchestrationCommand.PromoteQueuedRun(uuid(), threadId, runId, targetRunId))

    override suspend fun edit(threadId: String, runId: String, text: String): DispatchResult {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "A queued message cannot be empty." }
        return live.dispatch(ClientOrchestrationCommand.EditQueuedRun(uuid(), threadId, runId, trimmed))
    }
}

/** Sends a command once to the active environment without recording it in the durable outbox. */
class LiveCommandDispatcher(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val client: T3RpcClient,
) : CommandDispatcher {
    override suspend fun dispatch(command: ClientOrchestrationCommand): DispatchResult {
        val environment = requireNotNull(environments.activeEnvironment.value) { "No environment is selected." }
        val token = requireNotNull(credentials.read(environment.id)) { "This environment needs to be paired again." }
        return client.dispatch(environment.baseUrl, token, command)
    }
}

private val ActiveRunStatuses = setOf("preparing", "starting", "running", "waiting")

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.present(key: String): Boolean = this[key].let { it != null && it != JsonNull }

private fun uuid(): String = UUID.randomUUID().toString()
