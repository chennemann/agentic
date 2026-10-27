package de.chennemann.agentic.domain.guestshare

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.AttachmentAssetClient
import de.chennemann.agentic.data.t3.EnvironmentAuthClient
import de.chennemann.agentic.data.t3.GuestShareRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.t3.contract.GuestShareContracts
import de.chennemann.agentic.t3.contract.GuestShareCreateResult
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.ThreadGuestShare
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** A thread's guest link: who redeemed it, when it ends, and the guest messages awaiting the owner. */
data class GuestShare(
    val threadId: String,
    val id: String,
    val host: String,
    val expiresAt: Instant?,
    val guestName: String?,
    val pending: List<GuestMessage>,
) {
    fun isExpired(now: Instant): Boolean = expiresAt != null && !now.isBefore(expiresAt)
}

data class GuestMessage(
    val id: String,
    val text: String,
    val submittedAt: Instant?,
    val attachments: List<GuestAttachment>,
)

data class GuestAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

data class GuestShareLink(val url: String, val share: GuestShare)

/** Whether this paired environment lets Agentic create a guest link right now. */
sealed interface GuestShareAvailability {
    data object Available : GuestShareAvailability

    /** The token was not issued with the permission the server requires to hand out thread access. */
    data object MissingPermission : GuestShareAvailability

    /** The environment's Labs setting is off; it refuses new links and pauses existing ones. */
    data class Disabled(val canEnable: Boolean) : GuestShareAvailability
}

fun ThreadGuestShare.toDomain(threadId: String): GuestShare = GuestShare(
    threadId = threadId,
    id = id,
    host = host,
    expiresAt = runCatching { Instant.parse(expiresAt) }.getOrNull(),
    guestName = guestName?.takeIf { it.isNotBlank() },
    pending = messages.filter { it.status == "pending" }.map { message ->
        GuestMessage(
            id = message.id,
            text = message.text,
            submittedAt = runCatching { Instant.parse(message.submittedAt) }.getOrNull(),
            attachments = message.attachments.map { GuestAttachment(it.id, it.name, it.mimeType, it.sizeBytes) },
        )
    },
)

class GuestShareService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val auth: EnvironmentAuthClient,
    private val rpc: GuestShareRpcClient,
    private val assets: AttachmentAssetClient,
    repository: OrchestrationRepository,
    private val commandIds: () -> String = { UUID.randomUUID().toString() },
) {
    /** The focused thread's share, following live projection updates. */
    val focusedShare: Flow<GuestShare?> = repository.focusedThread
        .map { state ->
            val snapshot = state.value ?: return@map null
            GuestShareContracts.share(snapshot.projection.thread)?.toDomain(snapshot.thread.id)
        }
        .distinctUntilChanged()

    suspend fun availability(): GuestShareAvailability {
        val environment = active()
        val token = token(environment)
        val session = auth.session(environment.baseUrl, token)
        if (!session.grants(GuestShareContracts.CREATE_PERMISSION)) return GuestShareAvailability.MissingPermission
        val settings = rpc.guestShareCall(environment.baseUrl, token, GuestShareContracts.SETTINGS_METHOD, buildJsonObject {})
        if (!GuestShareContracts.sharingEnabled(settings.jsonObject)) {
            return GuestShareAvailability.Disabled(session.grants(GuestShareContracts.SETTINGS_PERMISSION))
        }
        return GuestShareAvailability.Available
    }

    suspend fun enableSharing() {
        call(GuestShareContracts.UPDATE_SETTINGS_METHOD, GuestShareContracts.enableSettingsPatch())
    }

    /** A new link replaces any current one, ending the previous guest's access. */
    suspend fun create(threadId: String): GuestShareLink {
        val result = T3Json.decodeFromJsonElement<GuestShareCreateResult>(
            call(GuestShareContracts.CREATE_METHOD, GuestShareContracts.createInput(threadId)),
        )
        return GuestShareLink(result.url, result.share.toDomain(threadId))
    }

    suspend fun stop(threadId: String) {
        dispatch(GuestShareContracts.close(commandIds(), threadId))
    }

    suspend fun approve(threadId: String, guestMessageId: String) {
        dispatch(GuestShareContracts.send(commandIds(), threadId, guestMessageId))
    }

    suspend fun reject(threadId: String, guestMessageId: String) {
        dispatch(GuestShareContracts.dismiss(commandIds(), threadId, guestMessageId))
    }

    suspend fun attachmentPreview(attachment: GuestAttachment): String {
        val environment = active()
        return assets.loadDataUrl(environment.baseUrl, token(environment), attachment.id, attachment.mimeType)
    }

    private suspend fun dispatch(command: JsonObject) {
        call(GuestShareContracts.DISPATCH_METHOD, command)
    }

    private suspend fun call(method: String, input: JsonObject) = active().let { environment ->
        rpc.guestShareCall(environment.baseUrl, token(environment), method, input)
    }

    private fun active(): SavedEnvironment =
        requireNotNull(environments.activeEnvironment.value) { "Select an environment first." }

    private suspend fun token(environment: SavedEnvironment): String =
        requireNotNull(credentials.read(environment.id)) { "Pair this environment again." }
}
