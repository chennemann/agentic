package de.chennemann.agentic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.ui.chat.ChatConnectionUi
import de.chennemann.agentic.ui.chat.canRetryConnection

@Composable
fun ConnectionStatusBanner(
    connection: ChatConnectionUi,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (connection is ChatConnectionUi.Live) return

    val retry = connection.canRetryConnection
    val busy = connection is ChatConnectionUi.Connecting ||
        connection is ChatConnectionUi.Synchronizing ||
        connection is ChatConnectionUi.Reconnecting
    val error = connection is ChatConnectionUi.Blocked ||
        connection is ChatConnectionUi.Unsupported ||
        connection is ChatConnectionUi.Failed

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(2.dp),
                    strokeWidth = 2.dp,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = connection.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                connection.traceId()?.let {
                    Text(
                        text = "Trace ID: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (retry) {
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

private fun ChatConnectionUi.message(): String = when (this) {
    ChatConnectionUi.Live -> ""
    is ChatConnectionUi.Cached -> message
    is ChatConnectionUi.Connecting -> message
    is ChatConnectionUi.Synchronizing -> message
    is ChatConnectionUi.Reconnecting -> message
    is ChatConnectionUi.Blocked -> message
    is ChatConnectionUi.Unsupported -> message
    is ChatConnectionUi.Failed -> message
}

private fun ChatConnectionUi.traceId(): String? = when (this) {
    is ChatConnectionUi.Reconnecting -> traceId
    is ChatConnectionUi.Blocked -> traceId
    is ChatConnectionUi.Unsupported -> traceId
    is ChatConnectionUi.Failed -> traceId
    else -> null
}
