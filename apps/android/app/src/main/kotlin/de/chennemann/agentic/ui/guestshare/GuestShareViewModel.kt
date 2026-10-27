package de.chennemann.agentic.ui.guestshare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.attachments.formatByteSize
import de.chennemann.agentic.domain.guestshare.GuestShare
import de.chennemann.agentic.domain.guestshare.GuestShareAvailability
import de.chennemann.agentic.domain.guestshare.GuestShareService
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GuestShareUiState(
    val threadId: String? = null,
    val active: ActiveGuestShareUi? = null,
    val pendingMessages: List<GuestPendingMessageUi> = emptyList(),
    val sheet: GuestShareSheetUi? = null,
    val errorMessage: String? = null,
)

data class ActiveGuestShareUi(
    val status: String,
    val expiry: String,
    val expired: Boolean,
    val stopping: Boolean,
)

data class GuestPendingMessageUi(
    val id: String,
    val guestName: String,
    val text: String,
    val attachments: List<GuestAttachmentUi>,
    val busy: Boolean,
)

data class GuestAttachmentUi(
    val id: String,
    val name: String,
    val sizeLabel: String,
    val isImage: Boolean,
    val previewDataUrl: String?,
)

data class GuestShareSheetUi(
    val threadId: String,
    val phase: GuestShareSheetPhase,
    val replacesActiveLink: Boolean,
)

sealed interface GuestShareSheetPhase {
    data object Checking : GuestShareSheetPhase

    data class Unavailable(val reason: String, val canEnable: Boolean = false, val enabling: Boolean = false) :
        GuestShareSheetPhase

    data object Ready : GuestShareSheetPhase

    data object Creating : GuestShareSheetPhase

    data class Created(val url: String) : GuestShareSheetPhase

    data class Failed(val message: String) : GuestShareSheetPhase
}

sealed interface GuestShareUiEvent {
    data class SheetRequested(val threadId: String) : GuestShareUiEvent

    data object SheetDismissed : GuestShareUiEvent

    data object EnableRequested : GuestShareUiEvent

    data object CreateRequested : GuestShareUiEvent

    data object StopRequested : GuestShareUiEvent

    data class ApproveRequested(val guestMessageId: String) : GuestShareUiEvent

    data class RejectRequested(val guestMessageId: String) : GuestShareUiEvent

    data class AttachmentPreviewRequested(val attachmentId: String) : GuestShareUiEvent

    data object ErrorDismissed : GuestShareUiEvent
}

const val GuestShareVisibilityWarning =
    "The guest sees this thread as you do, including the agent's reasoning and expanded command output. " +
        "Each message they send waits until you approve it."

const val GuestShareMissingPermissionReason =
    "This pairing cannot create guest links: the server requires the access:write permission, which " +
        "Agentic's pairing does not request. Create the link from T3 Code on the desktop or web. You can " +
        "still follow, approve, and stop an existing share here."

const val GuestShareDisabledReason =
    "Guest links are off on this environment. They are a Labs feature in the server's settings, and " +
        "also need a running T3 Connect tunnel."

private data class Transient(
    val sheet: GuestShareSheetUi? = null,
    val stopping: Boolean = false,
    val busyMessageIds: Set<String> = emptySet(),
    val previews: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
)

class GuestShareViewModel(
    private val service: GuestShareService,
) : ViewModel() {
    private val transient = MutableStateFlow(Transient())
    private val now = flow {
        while (true) {
            emit(Instant.now())
            delay(30_000)
        }
    }
    private val requestedPreviews = mutableSetOf<String>()
    private val focusedShare = service.focusedShare.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val share: GuestShare? get() = focusedShare.value

    val state: StateFlow<GuestShareUiState> = combine(focusedShare, transient, now) { share, transient, now ->
        guestShareUiState(
            share = share,
            now = now,
            sheet = transient.sheet,
            stopping = transient.stopping,
            busyMessageIds = transient.busyMessageIds,
            previews = transient.previews,
            errorMessage = transient.errorMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GuestShareUiState())

    fun onEvent(event: GuestShareUiEvent) {
        when (event) {
            is GuestShareUiEvent.SheetRequested -> open(event.threadId)
            GuestShareUiEvent.SheetDismissed -> transient.update {
                if (it.sheet?.phase == GuestShareSheetPhase.Creating) it else it.copy(sheet = null)
            }
            GuestShareUiEvent.EnableRequested -> enable()
            GuestShareUiEvent.CreateRequested -> create()
            GuestShareUiEvent.StopRequested -> stop()
            is GuestShareUiEvent.ApproveRequested -> resolve(event.guestMessageId, approve = true)
            is GuestShareUiEvent.RejectRequested -> resolve(event.guestMessageId, approve = false)
            is GuestShareUiEvent.AttachmentPreviewRequested -> preview(event.attachmentId)
            GuestShareUiEvent.ErrorDismissed -> transient.update { it.copy(errorMessage = null) }
        }
    }

    private fun open(threadId: String) {
        transient.update {
            it.copy(sheet = GuestShareSheetUi(threadId, GuestShareSheetPhase.Checking, share?.threadId == threadId))
        }
        checkAvailability(threadId)
    }

    private fun checkAvailability(threadId: String) {
        viewModelScope.launch {
            val phase = try {
                when (val availability = service.availability()) {
                    GuestShareAvailability.Available -> GuestShareSheetPhase.Ready
                    GuestShareAvailability.MissingPermission ->
                        GuestShareSheetPhase.Unavailable(GuestShareMissingPermissionReason)
                    is GuestShareAvailability.Disabled ->
                        GuestShareSheetPhase.Unavailable(GuestShareDisabledReason, canEnable = availability.canEnable)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                GuestShareSheetPhase.Failed(error.message ?: "Could not check whether guest links are available.")
            }
            updateSheet(threadId) { phase }
        }
    }

    private fun enable() {
        val sheet = transient.value.sheet ?: return
        val phase = sheet.phase as? GuestShareSheetPhase.Unavailable ?: return
        if (!phase.canEnable || phase.enabling) return
        updateSheet(sheet.threadId) { phase.copy(enabling = true) }
        viewModelScope.launch {
            try {
                service.enableSharing()
                checkAvailability(sheet.threadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateSheet(sheet.threadId) { GuestShareSheetPhase.Failed(error.message ?: "Could not turn on guest links.") }
            }
        }
    }

    private fun create() {
        val sheet = transient.value.sheet ?: return
        if (sheet.phase != GuestShareSheetPhase.Ready) return
        updateSheet(sheet.threadId) { GuestShareSheetPhase.Creating }
        viewModelScope.launch {
            val phase = try {
                GuestShareSheetPhase.Created(service.create(sheet.threadId).url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                GuestShareSheetPhase.Failed(error.message ?: "Could not create a guest link.")
            }
            updateSheet(sheet.threadId) { phase }
        }
    }

    private fun stop() {
        val threadId = share?.threadId ?: return
        if (transient.value.stopping) return
        transient.update { it.copy(stopping = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                service.stop(threadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                transient.update { it.copy(errorMessage = error.message ?: "Could not stop sharing.") }
            } finally {
                transient.update { it.copy(stopping = false) }
            }
        }
    }

    /** One request per message at a time: a second tap while the first is in flight would send twice. */
    private fun resolve(guestMessageId: String, approve: Boolean) {
        val threadId = share?.threadId ?: return
        if (guestMessageId in transient.value.busyMessageIds) return
        transient.update { it.copy(busyMessageIds = it.busyMessageIds + guestMessageId, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (approve) service.approve(threadId, guestMessageId) else service.reject(threadId, guestMessageId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val fallback = if (approve) "Could not send the guest message." else "Could not dismiss the guest message."
                transient.update { it.copy(errorMessage = error.message ?: fallback) }
            } finally {
                transient.update { it.copy(busyMessageIds = it.busyMessageIds - guestMessageId) }
            }
        }
    }

    private fun preview(attachmentId: String) {
        val attachment = share?.pending?.flatMap { it.attachments }?.firstOrNull { it.id == attachmentId } ?: return
        if (!attachment.isImage || !requestedPreviews.add(attachmentId)) return
        viewModelScope.launch {
            try {
                val dataUrl = service.attachmentPreview(attachment)
                transient.update { it.copy(previews = it.previews + (attachmentId to dataUrl)) }
            } catch (cancelled: CancellationException) {
                requestedPreviews.remove(attachmentId)
                throw cancelled
            } catch (_: Exception) {
                requestedPreviews.remove(attachmentId)
            }
        }
    }

    private fun updateSheet(threadId: String, phase: () -> GuestShareSheetPhase) {
        transient.update { current ->
            val sheet = current.sheet?.takeIf { it.threadId == threadId } ?: return@update current
            current.copy(sheet = sheet.copy(phase = phase()))
        }
    }
}

fun guestShareUiState(
    share: GuestShare?,
    now: Instant,
    sheet: GuestShareSheetUi? = null,
    stopping: Boolean = false,
    busyMessageIds: Set<String> = emptySet(),
    previews: Map<String, String> = emptyMap(),
    errorMessage: String? = null,
): GuestShareUiState {
    val guestName = share?.guestName ?: "Guest"
    return GuestShareUiState(
        threadId = share?.threadId,
        active = share?.let {
            val expired = it.isExpired(now)
            ActiveGuestShareUi(
                status = when {
                    expired -> "Guest link expired"
                    it.guestName == null -> "Guest link active · not opened yet"
                    else -> "${it.guestName} is watching as a guest"
                },
                expiry = guestShareExpiryLabel(it.expiresAt, now),
                expired = expired,
                stopping = stopping,
            )
        },
        pendingMessages = share?.pending.orEmpty().map { message ->
            GuestPendingMessageUi(
                id = message.id,
                guestName = guestName,
                text = message.text,
                attachments = message.attachments.map {
                    GuestAttachmentUi(it.id, it.name, formatByteSize(it.sizeBytes), it.isImage, previews[it.id])
                },
                busy = message.id in busyMessageIds,
            )
        },
        sheet = sheet,
        errorMessage = errorMessage,
    )
}

fun guestShareExpiryLabel(expiresAt: Instant?, now: Instant): String {
    expiresAt ?: return "Expiry unknown"
    val remaining = Duration.between(now, expiresAt)
    if (!remaining.isNegative && !remaining.isZero) {
        return "Expires in ${duration(remaining)}"
    }
    return "Expired ${duration(remaining.negated())} ago"
}

private fun duration(value: Duration): String {
    val minutes = value.toMinutes()
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "$minutes min"
        else -> {
            val hours = value.toHours()
            val rest = minutes - hours * 60
            if (rest == 0L || hours >= 10) "$hours h" else "$hours h $rest min"
        }
    }
}
