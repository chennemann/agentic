package de.chennemann.agentic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import de.chennemann.agentic.icons.Close
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.ComposerUiState
import de.chennemann.agentic.ui.chat.QueuedMessageEditUi
import de.chennemann.agentic.ui.chat.QueuedSendActionUi

private val QueueMuted = Color(0xFFB8B8B8)

/**
 * Messages waiting in the thread's server queue. Tapping a message edits it; its Steer or Send now
 * button sits apart from the editable text, so a near miss never opens the editor.
 */
@Composable
internal fun QueuedMessages(state: ComposerUiState, onEvent: (ChatUiEvent) -> Unit) {
    if (state.queuedMessages.isEmpty()) return
    val busy = state.queuedBusyId != null
    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).testTag(QueuedMessagesTestTag)) {
        if (state.queueHeld) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Queue paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = QueueMuted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEvent(ChatUiEvent.QueueResumeRequested) }, enabled = !busy) {
                    Text("Resume queue")
                }
            }
        }
        state.queuedEditBlockedReason?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = QueueMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        state.queuedMessages.forEachIndexed { index, message ->
            val editing = state.queuedEdit?.id == message.id
            val canEdit = message.editable && state.queuedEditBlockedReason == null && !busy && state.queuedEdit == null
            Row(
                Modifier.fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.weight(1f)
                        .clickable(enabled = canEdit, role = Role.Button, onClickLabel = "Edit queued message") {
                            onEvent(ChatUiEvent.QueuedMessageEditRequested(message.id))
                        }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        if (editing) "Queued · editing" else "Queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = QueueMuted,
                    )
                    Text(
                        message.text.ifBlank { if (message.attachmentCount > 0) "Attachments" else "Queued message" },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (editing) QueueMuted else Color.Unspecified,
                    )
                    if (message.attachmentCount > 0 && message.text.isNotBlank()) {
                        Text(
                            if (message.attachmentCount == 1) "1 attachment" else "${message.attachmentCount} attachments",
                            style = MaterialTheme.typography.labelSmall,
                            color = QueueMuted,
                        )
                    }
                }
                state.queuedSendAction?.let { action ->
                    val sendLabel = if (action == QueuedSendActionUi.SEND_NOW) "Send now" else "Steer"
                    FilledTonalButton(
                        onClick = { onEvent(ChatUiEvent.QueuedMessageSendRequested(message.id)) },
                        enabled = !busy && !editing,
                        modifier = Modifier.fillMaxHeight().semantics {
                            contentDescription = if (action == QueuedSendActionUi.SEND_NOW) {
                                "Send message ${index + 1} now"
                            } else {
                                "Steer with message ${index + 1} now"
                            }
                        },
                    ) {
                        if (state.queuedBusyId == message.id) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(sendLabel)
                        }
                    }
                }
                IconButton(
                    onClick = { onEvent(ChatUiEvent.QueuedMessageRemoved(message.id)) },
                    enabled = !busy && !(editing && state.queuedEdit?.saving == true),
                ) {
                    Icon(
                        Icons.Close,
                        contentDescription = "Remove queued message ${index + 1}",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
    state.queuedEdit?.let { QueuedMessageEditor(it, onEvent) }
}

/** Edits one queued message; it locks while the save is out, so the save keeps its claim until it settles. */
@Composable
private fun QueuedMessageEditor(edit: QueuedMessageEditUi, onEvent: (ChatUiEvent) -> Unit) {
    var text by rememberSaveable(edit.id) { mutableStateOf(edit.text) }
    AlertDialog(
        onDismissRequest = { if (!edit.saving) onEvent(ChatUiEvent.QueuedMessageEditCancelled) },
        properties = DialogProperties(dismissOnBackPress = !edit.saving, dismissOnClickOutside = !edit.saving),
        title = { Text("Edit queued message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !edit.saving,
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag(QueuedEditFieldTestTag),
                )
                edit.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEvent(ChatUiEvent.QueuedMessageEditSaved(text)) },
                enabled = !edit.saving && text.isNotBlank(),
            ) {
                if (edit.saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(ChatUiEvent.QueuedMessageEditCancelled) }, enabled = !edit.saving) {
                Text("Cancel")
            }
        },
    )
}

internal const val QueuedMessagesTestTag = "queued-messages"
internal const val QueuedEditFieldTestTag = "queued-edit-field"
