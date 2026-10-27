package de.chennemann.agentic.ui.guestshare

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.ui.components.decodeDataUrlBitmap

/** Guest-share state and intents the chat screen hosts; absent where guest sharing is not wired. */
data class GuestShareController(
    val state: GuestShareUiState,
    val onEvent: (GuestShareUiEvent) -> Unit,
)

val LocalGuestShare = compositionLocalOf<GuestShareController?> { null }

/** Owner status for a shared thread above the composer, with the way to end the share. */
@Composable
fun GuestShareBar(controller: GuestShareController, modifier: Modifier = Modifier) {
    val active = controller.state.active
    val error = controller.state.errorMessage
    if (active == null && error == null) return
    var confirmStop by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
            if (active != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            active.status,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${active.expiry} · sees reasoning and command output",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { confirmStop = true }, enabled = !active.stopping) {
                        Text(if (active.stopping) "Stopping…" else if (active.expired) "Clear" else "Stop sharing")
                    }
                }
            }
            if (error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { controller.onEvent(GuestShareUiEvent.ErrorDismissed) }) { Text("Dismiss") }
                }
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop sharing?") },
            text = { Text("The guest loses access, the link stops working, and messages still waiting are dropped.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    controller.onEvent(GuestShareUiEvent.StopRequested)
                }) {
                    Text("Stop sharing", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

/** Guest messages wait at the end of the timeline until the owner approves or rejects each one. */
fun LazyListScope.guestPendingMessages(controller: GuestShareController?) {
    if (controller == null) return
    items(controller.state.pendingMessages, key = { "guest-message:${it.id}" }) { message ->
        GuestPendingMessage(message, controller.onEvent)
    }
}

@Composable
private fun GuestPendingMessage(message: GuestPendingMessageUi, onEvent: (GuestShareUiEvent) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text(
            "${message.guestName} (guest) · awaiting your approval",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp, end = 4.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.85f),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message.text.isNotBlank()) {
                    SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                }
                if (message.attachments.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        message.attachments.forEach { GuestAttachment(it, onEvent) }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            if (message.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            OutlinedButton(
                onClick = { onEvent(GuestShareUiEvent.RejectRequested(message.id)) },
                enabled = !message.busy,
            ) { Text("Reject") }
            Button(
                onClick = { onEvent(GuestShareUiEvent.ApproveRequested(message.id)) },
                enabled = !message.busy,
            ) { Text("Approve") }
        }
    }
}

@Composable
private fun GuestAttachment(attachment: GuestAttachmentUi, onEvent: (GuestShareUiEvent) -> Unit) {
    if (attachment.isImage) {
        LaunchedEffect(attachment.id, attachment.previewDataUrl) {
            if (attachment.previewDataUrl == null) onEvent(GuestShareUiEvent.AttachmentPreviewRequested(attachment.id))
        }
        val bitmap = remember(attachment.previewDataUrl) {
            attachment.previewDataUrl?.let { decodeDataUrlBitmap(it, 720) }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)),
            )
            return
        }
    }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 200.dp)) {
            Text(attachment.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(attachment.sizeLabel, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GuestShareSheetHost(controller: GuestShareController) {
    val sheet = controller.state.sheet ?: return
    val onEvent = controller.onEvent
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = { onEvent(GuestShareUiEvent.SheetDismissed) }) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Share with guest", style = MaterialTheme.typography.titleLarge)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    GuestShareVisibilityWarning,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
            when (val phase = sheet.phase) {
                GuestShareSheetPhase.Checking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Checking whether this environment allows guest links…")
                }
                is GuestShareSheetPhase.Unavailable -> {
                    Text(phase.reason, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (phase.canEnable) {
                            Button(onClick = { onEvent(GuestShareUiEvent.EnableRequested) }, enabled = !phase.enabling) {
                                Text(if (phase.enabling) "Turning on…" else "Turn on guest links")
                            }
                        }
                        TextButton(onClick = { onEvent(GuestShareUiEvent.SheetDismissed) }) { Text("Close") }
                    }
                }
                GuestShareSheetPhase.Ready, GuestShareSheetPhase.Creating -> {
                    val creating = phase == GuestShareSheetPhase.Creating
                    Text(
                        if (sheet.replacesActiveLink) {
                            "A new link ends the current one and removes its guest."
                        } else {
                            "The link works for one guest and expires in 24 hours. It needs this environment's T3 Connect tunnel."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onEvent(GuestShareUiEvent.CreateRequested) }, enabled = !creating) {
                            Text(if (creating) "Creating…" else "Create link")
                        }
                        TextButton(onClick = { onEvent(GuestShareUiEvent.SheetDismissed) }, enabled = !creating) {
                            Text("Cancel")
                        }
                    }
                }
                is GuestShareSheetPhase.Created -> {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        SelectionContainer {
                            Text(
                                phase.url,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp).heightIn(max = 96.dp),
                            )
                        }
                    }
                    Text(
                        "Send it only to the person you want to watch. Anyone who opens it first becomes the guest.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { shareGuestLink(context, phase.url) }) { Text("Share…") }
                        OutlinedButton(onClick = { copyGuestLink(context, phase.url) }) { Text("Copy") }
                        TextButton(onClick = { onEvent(GuestShareUiEvent.SheetDismissed) }) { Text("Done") }
                    }
                }
                is GuestShareSheetPhase.Failed -> {
                    Text(phase.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onEvent(GuestShareUiEvent.SheetRequested(sheet.threadId)) }) { Text("Try again") }
                        TextButton(onClick = { onEvent(GuestShareUiEvent.SheetDismissed) }) { Text("Close") }
                    }
                }
            }
        }
    }
}

private fun shareGuestLink(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(send, "Share guest link"))
}

/** The link is a credential, so the clipboard is told to keep it out of previews. */
private fun copyGuestLink(context: Context, url: String) {
    val clip = ClipData.newPlainText("Guest link", url).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
