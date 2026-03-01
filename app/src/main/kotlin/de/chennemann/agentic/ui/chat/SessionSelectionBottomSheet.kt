package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.domain.session.SessionState
import de.chennemann.agentic.icons.Add
import de.chennemann.agentic.icons.Archive
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Pin
import de.chennemann.agentic.icons.PinOutline
import de.chennemann.agentic.icons.Rename
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SessionSelectionBottomSheet(
    menu: SessionSelectionUiState?,
    onEvent: (SessionSelectionEvent) -> Unit,
) {
    if (menu == null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Loading sessions...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var renameId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val rows = menu.sessions
        .sortedWith(
            compareByDescending<SessionState> { menu.pinned.contains(it.id) }
                .thenByDescending { it.updatedAt ?: 0L }
                .thenByDescending { it.id }
        )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = folderName(menu.project),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { onEvent(SessionSelectionEvent.NewSessionRequested) },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Add,
                    contentDescription = "New session",
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (menu.loading && rows.isEmpty()) {
                item {
                    Text(
                        text = "Loading sessions...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (menu.sessions.isEmpty() && !menu.loading) {
                item {
                    Text(
                        text = "No sessions found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(rows, key = { it.id }) { session ->
                val pinned = menu.pinned.contains(session.id)
                val systemPinned = menu.systemPinned.contains(session.id)
                val renaming = renameId == session.id

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = if (renaming) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .clickable { onEvent(SessionSelectionEvent.SessionSelected(session)) }
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = quickSwitchSessionSubtitle(session, folderName(menu.project), menu.worktree),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = {
                                renameId = session.id
                                renameDraft = session.title
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rename,
                                contentDescription = "Rename session",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onEvent(SessionSelectionEvent.SessionArchiveRequested(session)) }) {
                            Icon(
                                imageVector = Icons.Archive,
                                contentDescription = "Archive session",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onEvent(SessionSelectionEvent.SessionPinToggled(session, systemPinned)) }) {
                            Icon(
                                imageVector = if (pinned) Icons.Pin else Icons.PinOutline,
                                contentDescription = if (pinned) {
                                    "Remove from quick switch"
                                } else {
                                    "Pin in quick switch"
                                },
                                tint = if (pinned) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (renaming) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TextField(
                                value = renameDraft,
                                onValueChange = { renameDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { renameId = null }) {
                                    Text("Cancel")
                                }
                                TextButton(
                                    onClick = {
                                        onEvent(SessionSelectionEvent.RenameSessionSubmitted(session, renameDraft))
                                        renameId = null
                                    },
                                    enabled = renameDraft.trim().isNotBlank(),
                                ) {
                                    Text("Save")
                                }
                            }
                        }
                    }
                }
            }

            if (menu.canLoadMore) {
                item {
                    TextButton(
                        onClick = { onEvent(SessionSelectionEvent.MoreSessionsRequested) },
                        enabled = !menu.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (menu.loading) "Loading..." else "Load more")
                    }
                }
            }
        }
    }
}

private fun quickSwitchSessionSubtitle(session: SessionState, project: String, worktree: String): String {
    val updated = session.updatedAt?.let {
        val value = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        "Updated ${QuickSwitchSessionFormatter.format(value)}"
    } ?: "Updated unknown"
    if (workspaceId(session.directory) == workspaceId(worktree)) return updated
    return "$updated | $project"
}

private fun folderName(path: String): String {
    val value = path.trim().trimEnd('/', '\\')
    if (value.isBlank()) return path
    val index = maxOf(value.lastIndexOf('/'), value.lastIndexOf('\\'))
    if (index < 0) return value
    val name = value.substring(index + 1)
    if (name.isBlank()) return value
    return name
}

private fun workspaceId(path: String): String {
    return path.trimEnd('/', '\\')
}

private val QuickSwitchSessionFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm")
