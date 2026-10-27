package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.ChevronUp
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText

internal val ChatMessageOriginUi.foldsToRow: Boolean
    get() = this == ChatMessageOriginUi.AUTOMATION || this == ChatMessageOriginUi.AGENT

/** Automation runbooks and messages from other agents fold to one line so the user's own messages stay easy to find. */
@Composable
internal fun FoldedMessageRow(message: ChatMessageUi) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val label = if (message.origin == ChatMessageOriginUi.AUTOMATION) "Automation" else "Agent"
    val summary = remember(message.content) { foldedMessageSummary(message.content) }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Card(
            modifier = if (expanded) Modifier.fillMaxWidth() else Modifier.widthIn(max = 420.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(
                modifier = Modifier
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Hide the message" else "Show the message",
                    ) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = expanded),
                )
                Icon(
                    imageVector = if (expanded) Icons.ChevronUp else Icons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
            if (expanded) {
                SelectionContainer {
                    StreamingMarkdownText(
                        content = message.content,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        streaming = false,
                        inlineCode = SpanStyle(color = MaterialTheme.colorScheme.onPrimaryContainer),
                    )
                }
            }
        }
    }
}

/** The settled-work toggle, with the turn's diffstat when it changed files. */
@Composable
internal fun WorkSummaryLabel(summary: ChatTimelineItemUi.WorkSummary, expanded: Boolean) {
    val additions = Color(0xFF2E7D32)
    val deletions = MaterialTheme.colorScheme.error
    Text(
        buildAnnotatedString {
            append(summary.label)
            summary.diffStat?.let { stat ->
                append(" · ")
                withStyle(SpanStyle(color = additions, fontWeight = FontWeight.Medium)) { append("+${stat.additions}") }
                append(" ")
                withStyle(SpanStyle(color = deletions, fontWeight = FontWeight.Medium)) { append("−${stat.deletions}") }
            }
            append(if (expanded) " - Hide work" else " - Show work")
        },
    )
}

private val PendingRequestsMinHeight = 160.dp
private val PendingRequestsMaxHeight = 560.dp

/** Room above the composer chrome that the response cards may use. */
private val PendingRequestsReservedHeight = 220.dp

/**
 * Pending requests may use the screen while the keyboard is closed; the screen area already shrinks
 * with the keyboard, so typing an answer leaves the cards a smaller share.
 */
internal fun pendingRequestsMaxHeight(screenAreaHeight: Dp): Dp =
    (screenAreaHeight - PendingRequestsReservedHeight).coerceIn(PendingRequestsMinHeight, PendingRequestsMaxHeight)
