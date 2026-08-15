@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package de.chennemann.agentic.t3.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
val T3Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    prettyPrint = false
}

@OptIn(ExperimentalSerializationApi::class)
val T3CommandJson = Json(T3Json) {
    explicitNulls = true
}

@Serializable
data class ExecutionEnvironmentDescriptor(
    val environmentId: String,
    val label: String,
    val platform: EnvironmentPlatform,
    val serverVersion: String,
    val capabilities: ExecutionEnvironmentCapabilities
)

@Serializable
data class EnvironmentPlatform(val os: String, val arch: String)

@Serializable
data class ExecutionEnvironmentCapabilities(
    val connectionProbe: Boolean = false,
    val repositoryIdentity: Boolean = false,
    val threadSettlement: Boolean = false,
    val threadSnooze: Boolean = false,
    val threadDeletion: Boolean = false,
    val threadWorktrees: Boolean = false
)

@Serializable
data class ServerAuthDescriptor(
    val policy: String,
    val bootstrapMethods: List<String>,
    val sessionMethods: List<String>,
    val sessionCookieName: String
)

@Serializable
data class AuthSession(
    val authenticated: Boolean,
    val auth: ServerAuthDescriptor,
    val sessionMethod: String? = null,
    val scopes: List<String> = emptyList(),
    val expiresAt: String? = null
)

@Serializable
data class TokenExchangeResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("issued_token_type")
    val issuedTokenType: String,
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("expires_in")
    val expiresIn: JsonElement,
    val scope: String
)

@Serializable
data class ServerConfig(
    val environment: ExecutionEnvironmentDescriptor,
    val auth: ServerAuthDescriptor,
    val providers: List<ProviderInstance>,
    val settings: ServerSettings = ServerSettings(),
    val shellResumeCompletionMarker: Boolean = false,
    val threadResumeCompletionMarker: Boolean = false
)

@Serializable
data class ServerSettings(val addProjectBaseDirectory: String = "")

@Serializable
data class FilesystemBrowseEntry(val name: String, val fullPath: String)

@Serializable
data class FilesystemBrowseResult(val parentPath: String, val entries: List<FilesystemBrowseEntry>)

@Serializable
data class VcsRef(
    val name: String,
    val isRemote: Boolean = false,
    val remoteName: String? = null,
    val current: Boolean,
    val isDefault: Boolean,
    val worktreePath: String? = null
)

@Serializable
data class VcsListRefsResult(
    val refs: List<VcsRef>,
    val isRepo: Boolean,
    val hasPrimaryRemote: Boolean,
    val nextCursor: Int? = null,
    val totalCount: Int
)

@Serializable
data class ProviderInstance(
    val instanceId: String,
    val displayName: String,
    val driver: String? = null,
    val enabled: Boolean = true,
    val installed: Boolean = true,
    val availability: String? = null,
    val status: String? = null,
    val models: List<ProviderModel> = emptyList(),
    val showInteractionModeToggle: Boolean = true,
    val slashCommands: List<SlashCommand> = emptyList(),
    val skills: List<JsonElement> = emptyList()
)

@Serializable
data class ProviderModel(
    val slug: String,
    val name: String,
    val isDefault: Boolean = false,
    val isCustom: Boolean = false,
    val capabilities: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class SlashCommand(val name: String, val description: String? = null, val input: JsonObject? = null)

@Serializable
data class ProviderOptionSelection(val id: String, val value: JsonPrimitive)

@Serializable
data class ModelSelection(val instanceId: String, val model: String, val options: List<ProviderOptionSelection>? = null)

@Serializable
data class OrchestrationProject(
    val id: String,
    val title: String,
    val workspaceRoot: String,
    val defaultModelSelection: ModelSelection? = null,
    val createdAt: String,
    val updatedAt: String,
    val scripts: List<JsonElement> = emptyList()
)

@Serializable
data class OrchestrationThreadShell(
    val id: String,
    val projectId: String,
    val title: String,
    val modelSelection: ModelSelection? = null,
    val interactionMode: String? = null,
    val runtimeMode: String? = null,
    val branch: String? = null,
    val worktreePath: String? = null,
    val archivedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val latestUserMessageAt: String? = null,
    val latestTurn: LatestTurn? = null,
    val session: ThreadSession? = null,
    val settledAt: String? = null,
    val settledOverride: String? = null,
    val snoozedAt: String? = null,
    val snoozedUntil: String? = null,
    val lastWakeReason: String? = null,
    val hasPendingApprovals: Boolean = false,
    val hasPendingUserInput: Boolean = false,
    val hasActionableProposedPlan: Boolean = false
)

@Serializable
data class LatestTurn(
    val turnId: String,
    val state: String,
    val assistantMessageId: String? = null,
    val requestedAt: String,
    val startedAt: String? = null,
    val completedAt: String? = null
)

@Serializable
data class ThreadSession(
    val threadId: String,
    val status: String,
    val providerName: String? = null,
    val providerInstanceId: String? = null,
    val activeTurnId: String? = null,
    val runtimeMode: String? = null,
    val lastError: String? = null,
    val updatedAt: String
)

@Serializable
data class OrchestrationShellSnapshot(
    val projects: List<OrchestrationProject>,
    val threads: List<OrchestrationThreadShell>,
    val snapshotSequence: Long,
    val updatedAt: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface OrchestrationShellStreamItem {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val snapshot: OrchestrationShellSnapshot) : OrchestrationShellStreamItem

    @Serializable
    @SerialName("project-upserted")
    data class ProjectUpserted(val sequence: Long, val project: OrchestrationProject) : OrchestrationShellStreamItem

    @Serializable
    @SerialName("project-removed")
    data class ProjectRemoved(val sequence: Long, val projectId: String) : OrchestrationShellStreamItem

    @Serializable
    @SerialName("thread-upserted")
    data class ThreadUpserted(val sequence: Long, val thread: OrchestrationThreadShell) : OrchestrationShellStreamItem

    @Serializable
    @SerialName("thread-removed")
    data class ThreadRemoved(val sequence: Long, val threadId: String) : OrchestrationShellStreamItem

    @Serializable
    @SerialName("synchronized")
    data object Synchronized : OrchestrationShellStreamItem
}

@Serializable
data class OrchestrationThreadDetailSnapshot(val snapshotSequence: Long, val thread: OrchestrationThreadDetail)

@Serializable
data class OrchestrationThreadDetail(
    val id: String,
    val projectId: String,
    val title: String,
    val modelSelection: ModelSelection? = null,
    val interactionMode: String? = null,
    val runtimeMode: String? = null,
    val branch: String? = null,
    val worktreePath: String? = null,
    val archivedAt: String? = null,
    val deletedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val latestTurn: LatestTurn? = null,
    val session: ThreadSession? = null,
    val settledAt: String? = null,
    val settledOverride: String? = null,
    val snoozedAt: String? = null,
    val snoozedUntil: String? = null,
    val lastWakeReason: String? = null,
    val messages: List<OrchestrationMessage> = emptyList(),
    val activities: List<OrchestrationActivity> = emptyList(),
    val proposedPlans: List<ProposedPlan> = emptyList(),
    val checkpoints: List<JsonElement> = emptyList()
)

@Serializable
data class OrchestrationMessage(
    val id: String,
    val turnId: String? = null,
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val attachments: List<JsonElement> = emptyList()
)

@Serializable
data class OrchestrationActivity(
    val id: String,
    val turnId: String? = null,
    val sequence: Long? = null,
    val kind: String,
    val tone: String,
    val summary: String,
    val payload: JsonObject,
    val createdAt: String
)

@Serializable
data class ProposedPlan(
    val id: String,
    val turnId: String,
    val planMarkdown: String,
    val implementationThreadId: String? = null,
    val implementedAt: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class OrchestrationEvent(
    val aggregateId: String,
    val aggregateKind: String,
    val eventId: String,
    val commandId: String,
    val correlationId: String,
    val causationEventId: String? = null,
    val sequence: Long,
    val type: String,
    val payload: JsonObject,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val occurredAt: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface OrchestrationThreadStreamItem {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val snapshot: OrchestrationThreadDetailSnapshot) : OrchestrationThreadStreamItem

    @Serializable
    @SerialName("event")
    data class Event(val event: OrchestrationEvent) : OrchestrationThreadStreamItem

    @Serializable
    @SerialName("synchronized")
    data object Synchronized : OrchestrationThreadStreamItem
}

@Serializable
data class DispatchResult(val sequence: Long)

@Serializable
data class TurnMessageInput(
    val messageId: String,
    @EncodeDefault
    val role: String = "user",
    val text: String,
    @EncodeDefault
    val attachments: List<JsonElement> = emptyList()
)

@Serializable
data class NewThreadBootstrap(
    val projectId: String,
    val title: String,
    val modelSelection: ModelSelection,
    val interactionMode: String,
    val runtimeMode: String,
    @EncodeDefault
    val branch: String? = null,
    @EncodeDefault
    val worktreePath: String? = null,
    val createdAt: String
)

@Serializable
data class PrepareWorktreeBootstrap(
    val projectCwd: String,
    val baseBranch: String,
    val branch: String? = null,
    val startFromOrigin: Boolean? = null
)

@Serializable
data class StartTurnBootstrap(
    val createThread: NewThreadBootstrap,
    val prepareWorktree: PrepareWorktreeBootstrap? = null,
    val runSetupScript: Boolean? = null
)

@Serializable
data class SourceProposedPlan(val threadId: String, val planId: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ClientOrchestrationCommand {
    val commandId: String

    @Serializable
    @SerialName("project.create")
    data class CreateProject(
        override val commandId: String,
        val projectId: String,
        val title: String,
        val workspaceRoot: String,
        @EncodeDefault
        val defaultModelSelection: ModelSelection? = null,
        val createdAt: String
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("project.meta.update")
    data class UpdateProjectMetadata(
        override val commandId: String,
        val projectId: String,
        val title: String? = null,
        val workspaceRoot: String? = null,
        val defaultModelSelection: ModelSelection? = null,
        val scripts: List<JsonElement>? = null
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("project.delete")
    data class DeleteProject(override val commandId: String, val projectId: String, val force: Boolean? = null) :
        ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.create")
    data class CreateThread(
        override val commandId: String,
        val threadId: String,
        val projectId: String,
        val title: String,
        val modelSelection: ModelSelection,
        val interactionMode: String,
        val runtimeMode: String,
        @EncodeDefault
        val branch: String? = null,
        @EncodeDefault
        val worktreePath: String? = null,
        val createdAt: String
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.turn.start")
    data class StartTurn(
        override val commandId: String,
        val threadId: String,
        val message: TurnMessageInput,
        val modelSelection: ModelSelection,
        val titleSeed: String? = null,
        val interactionMode: String,
        val runtimeMode: String,
        val createdAt: String,
        val bootstrap: StartTurnBootstrap? = null,
        val sourceProposedPlan: SourceProposedPlan? = null
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.turn.interrupt")
    data class InterruptTurn(
        override val commandId: String,
        val threadId: String,
        val turnId: String,
        val createdAt: String
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.session.stop")
    data class StopSession(override val commandId: String, val threadId: String, val createdAt: String) :
        ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.meta.update")
    data class UpdateThreadMetadata(
        override val commandId: String,
        val threadId: String,
        val title: String,
        val modelSelection: ModelSelection
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.archive")
    data class ArchiveThread(override val commandId: String, val threadId: String) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.delete")
    data class DeleteThread(override val commandId: String, val threadId: String) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.unarchive")
    data class UnarchiveThread(override val commandId: String, val threadId: String) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.settle")
    data class SettleThread(override val commandId: String, val threadId: String) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.unsettle")
    data class UnsettleThread(
        override val commandId: String,
        val threadId: String,
        @EncodeDefault
        val reason: String = "user"
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.snooze")
    data class SnoozeThread(override val commandId: String, val threadId: String, val snoozedUntil: String) :
        ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.unsnooze")
    data class UnsnoozeThread(
        override val commandId: String,
        val threadId: String,
        @EncodeDefault
        val reason: String = "user"
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.runtime-mode.set")
    data class SetRuntimeMode(
        override val commandId: String,
        val threadId: String,
        val runtimeMode: String,
        val createdAt: String
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.interaction-mode.set")
    data class SetInteractionMode(
        override val commandId: String,
        val threadId: String,
        val interactionMode: String,
        val createdAt: String
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.approval.respond")
    data class RespondToApproval(
        override val commandId: String,
        val threadId: String,
        val requestId: String,
        val decision: String,
        val createdAt: String
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.user-input.respond")
    data class RespondToUserInput(
        override val commandId: String,
        val threadId: String,
        val requestId: String,
        val answers: JsonObject,
        val createdAt: String
    ) : ClientOrchestrationCommand
}
