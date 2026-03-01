package de.chennemann.agentic.ui.manage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.domain.session.ServerState
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.ChevronUp
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.ui.theme.MobileTheme


@Composable
fun ServerCard(url: String, discoveredUrl: String?, status: ServerState, urlError: String?, onEvent: (ManageEvent) -> Unit) {
    val connected = status is ServerState.Connected
    val connecting = status is ServerState.Loading
    var expanded by rememberSaveable(connected) { mutableStateOf(!connected) }
    var edited by rememberSaveable { mutableStateOf(false) }
    val error = when {
        edited -> null
        else -> urlError
    }

    LaunchedEffect(connected) {
        if (connected) expanded = false
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                    }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (status is ServerState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = statusLabel(status),
                        color = statusColor(status),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.ChevronUp else Icons.ChevronDown,
                        contentDescription = if (expanded) "Collapse server" else "Expand server",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    var urlField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                        mutableStateOf(TextFieldValue(url))
                    }

                    LaunchedEffect(url) {
                        if (!edited && url != urlField.text) {
                            urlField = urlField.copy(text = url)
                        }
                    }

                    LaunchedEffect(url, urlField.text, edited) {
                        if (edited && url == urlField.text) edited = false
                    }

                    TextField(
                        value = urlField,
                        onValueChange = { v ->
                            urlField = v
                            edited = true
                        },
                        label = { Text("Server URL") },
                        singleLine = true,
                        enabled = !connecting,
                        isError = error != null,
                        supportingText = { if (error != null) Text(error) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (discoveredUrl != null) {
                        OutlinedButton(
                            onClick = {
                                urlField = urlField.copy(text = discoveredUrl)
                                edited = true
                            },
                            enabled = !connecting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Use discovered: $discoveredUrl",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            edited = false
                            onEvent(ManageEvent.Connect(urlField.text))
                        },
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (connecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            Text(if (connecting) "Connecting..." else "Connect")
                        }
                    }
                    val failed = status as? ServerState.Failed
                    if (failed != null) {
                        Text(
                            text = failed.reason,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun statusLabel(state: ServerState): String {
    return when (state) {
        is ServerState.Idle -> "Idle"
        is ServerState.Loading -> "Connecting"
        is ServerState.Connected -> "Connected ${state.version}"
        is ServerState.Failed -> "Failed"
    }
}

@Composable
private fun statusColor(state: ServerState): Color {
    return when (state) {
        is ServerState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        is ServerState.Loading -> MaterialTheme.colorScheme.tertiary
        is ServerState.Connected -> MaterialTheme.colorScheme.primary
        is ServerState.Failed -> MaterialTheme.colorScheme.error
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerCardPreviewDisconnectedWithDiscoveredUrl() {
    MobileTheme {
        ServerCard(
            url = "http://192.168.1.10:4096",
            discoveredUrl = "http://192.168.1.12:4096",
            status = ServerState.Idle,
            urlError = null,
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerCardPreviewDisconnected() {
    MobileTheme {
        ServerCard(
            url = "http://192.168.1.10:4096",
            discoveredUrl = null,
            status = ServerState.Idle,
            urlError = null,
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerCardPreviewConnected() {
    MobileTheme {
        ServerCard(
            url = "http://127.0.0.1:4096",
            discoveredUrl = null,
            status = ServerState.Connected("v0.14.2"),
            urlError = null,
            onEvent = {},
        )
    }
}
