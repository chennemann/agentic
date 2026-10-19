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
    val capabilities: ExecutionEnvironmentCapabilities,
    val orchestrationProtocolVersion: Int = 1
)

@Serializable
data class EnvironmentPlatform(val os: String, val arch: String)

@Serializable
data class ExecutionEnvironmentCapabilities(
    val connectionProbe: Boolean = false,
    val repositoryIdentity: Boolean = false,
    val threadSettlement: Boolean = false,
    val threadSnooze: Boolean = false,
    val usagePriceOverrides: Boolean = false,
    val attachmentUploads: Boolean = false,
    val inlineMessageContext: Boolean = false,
    val fileAttachments: FileAttachmentCapability? = null,
    val requiredWorktreeBootstrap: Boolean = false
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
    val threadResumeCompletionMarker: Boolean = false,
    val scratchWorkspaceRoot: String? = null,
    val usageLimitSources: List<UsageLimitSource> = emptyList(),
    val newProjectsRoot: String? = null
)

@Serializable
data class ServerConfigStreamEvent(
    val type: String,
    val config: ServerConfig? = null,
    val payload: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ProjectEnsureScratchResult(val projectId: String)

@Serializable
data class ProjectCreateNewResult(val projectId: String, val workspaceRoot: String, val commitError: String? = null)

@Serializable
data class ServerSettings(
    val addProjectBaseDirectory: String = "",
    val usagePriceOverrides: Map<String, UsageModelPrice> = emptyMap()
)

@Serializable
data class FilesystemBrowseEntry(val name: String, val fullPath: String)

@Serializable
data class FilesystemBrowseResult(val parentPath: String, val entries: List<FilesystemBrowseEntry>)

@Serializable
data class WorkspaceEntry(val path: String, val kind: String)

@Serializable
data class WorkspaceEntriesResult(val entries: List<WorkspaceEntry>, val truncated: Boolean)

@Serializable
data class WorkspaceFileResult(
    val relativePath: String,
    val contents: String,
    val byteLength: Long,
    val truncated: Boolean
)

@Serializable
data class TerminalSessionSnapshot(
    val threadId: String,
    val terminalId: String,
    val cwd: String,
    val worktreePath: String? = null,
    val status: String,
    val pid: Int? = null,
    val history: String = "",
    val exitCode: Int? = null,
    val exitSignal: Int? = null,
    val label: String,
    val updatedAt: String,
    val sequence: Long? = null
)

@Serializable
data class TerminalSummary(
    val threadId: String,
    val terminalId: String,
    val cwd: String,
    val worktreePath: String? = null,
    val status: String,
    val pid: Int? = null,
    val exitCode: Int? = null,
    val exitSignal: Int? = null,
    val hasRunningSubprocess: Boolean = false,
    val label: String,
    val updatedAt: String
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface TerminalMetadataEvent {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val terminals: List<TerminalSummary>) : TerminalMetadataEvent

    @Serializable
    @SerialName("upsert")
    data class Upsert(val terminal: TerminalSummary) : TerminalMetadataEvent

    @Serializable
    @SerialName("remove")
    data class Remove(val threadId: String, val terminalId: String) : TerminalMetadataEvent
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface TerminalAttachEvent {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val snapshot: TerminalSessionSnapshot) : TerminalAttachEvent

    @Serializable
    @SerialName("output")
    data class Output(val threadId: String, val terminalId: String, val data: String, val sequence: Long? = null) :
        TerminalAttachEvent

    @Serializable
    @SerialName("exited")
    data class Exited(
        val threadId: String,
        val terminalId: String,
        val exitCode: Int? = null,
        val exitSignal: Int? = null,
        val sequence: Long? = null
    ) : TerminalAttachEvent

    @Serializable
    @SerialName("closed")
    data class Closed(val threadId: String, val terminalId: String, val sequence: Long? = null) : TerminalAttachEvent

    @Serializable
    @SerialName("error")
    data class Error(val threadId: String, val terminalId: String, val message: String, val sequence: Long? = null) :
        TerminalAttachEvent

    @Serializable
    @SerialName("cleared")
    data class Cleared(val threadId: String, val terminalId: String, val sequence: Long? = null) : TerminalAttachEvent

    @Serializable
    @SerialName("restarted")
    data class Restarted(
        val threadId: String,
        val terminalId: String,
        val snapshot: TerminalSessionSnapshot,
        val sequence: Long? = null
    ) : TerminalAttachEvent

    @Serializable
    @SerialName("activity")
    data class Activity(
        val threadId: String,
        val terminalId: String,
        val hasRunningSubprocess: Boolean,
        val label: String,
        val sequence: Long? = null
    ) : TerminalAttachEvent
}

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
    val displayName: String? = null,
    val driver: String? = null,
    val accentColor: String? = null,
    val continuation: ProviderContinuation? = null,
    val requiresNewThreadForModelChange: Boolean = false,
    val enabled: Boolean = true,
    val installed: Boolean = true,
    val availability: String? = null,
    val status: String? = null,
    val models: List<ProviderModel> = emptyList(),
    val showInteractionModeToggle: Boolean = true,
    val slashCommands: List<SlashCommand> = emptyList(),
    val auth: ProviderAuth = ProviderAuth(),
    val usageLimits: ProviderUsageLimits? = null,
    val skills: List<JsonElement> = emptyList(),
    val workspaceSnapshots: List<ProviderWorkspaceSnapshot> = emptyList()
)

/** Instances sharing a group key can resume each other's sessions, so a thread may move between them. */
@Serializable
data class ProviderContinuation(val groupKey: String)

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
enum class ProjectScriptIcon {
    @SerialName("play")
    PLAY,

    @SerialName("test")
    TEST,

    @SerialName("lint")
    LINT,

    @SerialName("configure")
    CONFIGURE,

    @SerialName("build")
    BUILD,

    @SerialName("debug")
    DEBUG
}

@Serializable
data class ProjectScript(
    val id: String,
    val name: String,
    val command: String,
    val icon: ProjectScriptIcon,
    val runOnWorktreeCreate: Boolean,
    val previewUrl: String? = null,
    val autoOpenPreview: Boolean? = null
)

@Serializable
data class OrchestrationProject(
    val id: String,
    val title: String,
    val workspaceRoot: String,
    val defaultModelSelection: ModelSelection? = null,
    val createdAt: String,
    val updatedAt: String,
    val scripts: List<ProjectScript> = emptyList()
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
    val updatedAt: String = "",
    val archivedSequence: Long? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface OrchestrationShellStreamItem {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val snapshot: OrchestrationShellSnapshot, val repositoryIdentityRefresh: Boolean = false) :
        OrchestrationShellStreamItem

    @Serializable
    @SerialName("archive-snapshot")
    data class ArchiveSnapshot(val sequence: Long, val threads: List<OrchestrationThreadShell>) :
        OrchestrationShellStreamItem

    @Serializable
    @SerialName("archive-change")
    data class ArchiveChange(val sequence: Long, val threadId: String, val thread: OrchestrationThreadShell? = null) :
        OrchestrationShellStreamItem

    @Serializable
    @SerialName("project.updated")
    data class ProjectUpserted(val sequence: Long, val project: OrchestrationProject) : OrchestrationShellStreamItem

    @Serializable
    @SerialName("project.removed")
    data class ProjectRemoved(val sequence: Long, val projectId: String) : OrchestrationShellStreamItem

    @Serializable
    @SerialName("thread.updated")
    data class ThreadUpserted(val sequence: Long, val thread: OrchestrationThreadShell) : OrchestrationShellStreamItem

    @Serializable
    @SerialName("thread.removed")
    data class ThreadRemoved(val sequence: Long, val threadId: String) : OrchestrationShellStreamItem

    @Serializable
    @SerialName("synchronized")
    data object Synchronized : OrchestrationShellStreamItem

    @Serializable
    @SerialName("ignored")
    data class Ignored(val sequence: Long) : OrchestrationShellStreamItem
}

@Serializable
data class OrchestrationThreadDetailSnapshot(
    val snapshotSequence: Long,
    val thread: OrchestrationThreadDetail,
    val page: OrchestrationThreadDetailPage? = null,
    val projection: V2Projection
)

@Serializable
data class OrchestrationThreadDetailPage(val beforeCursor: String?, val hasMore: Boolean, val snapshotSequence: Long)

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
    val attachments: List<ChatAttachment> = emptyList(),
    val context: JsonObject? = null
)

@Serializable
data class ChatAttachment(
    @EncodeDefault val type: String = "image",
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long
)

@Serializable
data class UploadChatAttachment(
    @EncodeDefault val type: String = "image",
    /** Client-side id so message context records can bind to the attachment before upload. */
    val id: String? = null,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dataUrl: String = "",
    val source: JsonObject? = null
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

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface OrchestrationThreadStreamItem {
    @Serializable
    @SerialName("event")
    data class Event(val sequence: Long, val event: V2EventPayload) : OrchestrationThreadStreamItem

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val snapshot: OrchestrationThreadDetailSnapshot) : OrchestrationThreadStreamItem

    @Serializable
    @SerialName("synchronized")
    data object Synchronized : OrchestrationThreadStreamItem
}

@Serializable
data class DispatchResult(val sequence: Long)

@Serializable
data class AssetCreateUrlResult(val relativeUrl: String, val expiresAt: Long)

@Serializable
data class TurnMessageInput(
    val messageId: String,
    val text: String,
    @EncodeDefault
    val attachments: List<UploadChatAttachment> = emptyList(),
    /** Structured message context (`{ version: 1, records: [...] }`), e.g. image annotations. */
    val context: JsonObject? = null
)

@Serializable
data class NewThreadBootstrap(
    val projectId: String,
    val title: String,
    @EncodeDefault
    val branch: String? = null,
    @EncodeDefault
    val worktreePath: String? = null
)

@Serializable
data class PrepareWorktreeBootstrap(val baseBranch: String, val startFromOrigin: Boolean? = null)

@Serializable
data class StartTurnBootstrap(
    val createThread: NewThreadBootstrap,
    val prepareWorktree: PrepareWorktreeBootstrap? = null
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
        val defaultModelSelection: ModelSelection? = null
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
    @SerialName("thread.turn.start")
    data class StartTurn(
        override val commandId: String,
        val threadId: String,
        val message: TurnMessageInput,
        val modelSelection: ModelSelection,
        val interactionMode: String,
        val runtimeMode: String,
        val bootstrap: StartTurnBootstrap? = null,
        val sourceProposedPlan: SourceProposedPlan? = null
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.turn.interrupt")
    data class InterruptTurn(override val commandId: String, val threadId: String, val turnId: String? = null) :
        ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.meta.update")
    data class UpdateThreadMetadata(override val commandId: String, val threadId: String, val title: String) :
        ClientOrchestrationCommand

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
    data class SetRuntimeMode(override val commandId: String, val threadId: String, val runtimeMode: String) :
        ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.approval.respond")
    data class RespondToApproval(
        override val commandId: String,
        val threadId: String,
        val requestId: String,
        val decision: String
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.user-input.respond")
    data class RespondToUserInput(
        override val commandId: String,
        val threadId: String,
        val requestId: String,
        val answers: JsonObject
    ) : ClientOrchestrationCommand

    @Serializable
    @SerialName("thread.user-input.dismiss")
    data class DismissUserInput(override val commandId: String, val threadId: String, val requestId: String) :
        ClientOrchestrationCommand
}
