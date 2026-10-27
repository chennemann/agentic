package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.domain.attachments.SharedFileKind
import de.chennemann.agentic.domain.attachments.SharedFileSource
import de.chennemann.agentic.domain.attachments.formatByteSize
import de.chennemann.agentic.domain.attachments.sharedFileKind
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Paperclip
import de.chennemann.agentic.ui.files.InlineAudioCard
import de.chennemann.agentic.ui.files.LocalSharedFiles

/** Files attached to a message: audio plays in place, everything opens in the file viewer. */
@Composable
internal fun MessageFiles(files: List<ChatFileAttachmentUi>) {
    if (files.isEmpty()) return
    val host = LocalSharedFiles.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { file ->
            val source = SharedFileSource.Attachment(file.id, file.name, file.mimeType)
            if (sharedFileKind(file.name, file.mimeType) == SharedFileKind.Audio) {
                InlineAudioCard(source, file.name, sizeBytes = file.sizeBytes, onOpen = { host.open(source) })
                return@forEach
            }
            Surface(
                onClick = { host.open(source) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Paperclip, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = formatByteSize(file.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** An audio file a message links on its own line; without a thread it stays a plain label. */
@Composable
internal fun MessageAudio(segment: MessageSegment.Audio) {
    val host = LocalSharedFiles.current
    val threadId = host.threadId
    if (threadId == null) {
        Text(segment.label, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val source = SharedFileSource.HostPath(threadId, segment.path)
    InlineAudioCard(source, segment.label, onOpen = { host.open(source) })
}
