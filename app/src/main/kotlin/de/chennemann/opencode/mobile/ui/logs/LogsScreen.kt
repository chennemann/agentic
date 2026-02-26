package de.chennemann.opencode.mobile.ui.logs

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.opencode.mobile.domain.session.LogEntry
import de.chennemann.opencode.mobile.domain.session.LogLevel
import de.chennemann.opencode.mobile.domain.session.LogUnit
import de.chennemann.opencode.mobile.icons.FilterList
import de.chennemann.opencode.mobile.icons.Icons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(state: LogsUiState, onEvent: (LogsEvent) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    var copyOpen by rememberSaveable { mutableStateOf(false) }
    var copyCount by rememberSaveable { mutableStateOf(100f) }

    LaunchedEffect(state.rows, expanded, filterOpen) {
        val id = expanded ?: return@LaunchedEffect
        val selected = state.rows.indexOfFirst { it.id == id }
        if (selected < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo.any { it.key == id }
        if (visible) return@LaunchedEffect
        val start = if (filterOpen) 8 else 4
        list.scrollToItem(start + selected)
    }

    fun applyFromEntry(event: LogsEvent) {
        onEvent(event)
        expanded = null
        scope.launch {
            list.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = list,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("title") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Application Logs", style = MaterialTheme.typography.headlineSmall)
                    Text("${state.rows.size} entries", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { filterOpen = !filterOpen }) {
                    Icon(
                        imageVector = Icons.FilterList,
                        contentDescription = "Toggle filters",
                        tint = if (filterOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item("active") {
            ActiveFilters(state, onEvent)
        }
        item("project") {
            ValueFilterRow(
                title = "Project",
                selected = state.selectedProjectId,
                options = state.facet.projects.map { it.id to it.name },
                onSelect = { onEvent(LogsEvent.ProjectChanged(it)) },
            )
        }
        if (filterOpen) {
            item("time") {
                TimeFilterRow(
                    from = state.selectedFrom,
                    until = state.selectedUntil,
                    onFrom = { onEvent(LogsEvent.FromChanged(it)) },
                    onUntil = { onEvent(LogsEvent.UntilChanged(it)) },
                )
            }
            item("level") {
                ValueFilterRow(
                    title = "Level",
                    selected = state.selectedLevel?.key,
                    options = state.levels.map { it.key to it.name.uppercase() },
                    onSelect = { onEvent(LogsEvent.LevelChanged(it?.let(LogLevel::from))) },
                )
            }
            item("unit") {
                ValueFilterRow(
                    title = "Unit",
                    selected = state.selectedUnit?.key,
                    options = state.units.map { it.key to it.label },
                    onSelect = { onEvent(LogsEvent.UnitChanged(it?.let(LogUnit::from))) },
                )
            }
            item("query") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onEvent(LogsEvent.QueryChanged(it)) },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        copyCount = state.rows.size.coerceAtMost(100).coerceAtLeast(1).toFloat()
                        copyOpen = true
                    },
                    enabled = state.rows.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (copied) "Copied" else "Copy Logs")
                }
                OutlinedButton(
                    onClick = { onEvent(LogsEvent.BackRequested) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Back")
                }
            }
        }
        if (state.rows.isEmpty()) {
            item("empty") {
                Text("No logs match active filters", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@LazyColumn
        }
        itemsIndexed(state.rows, key = { _, item -> item.id }) { idx, item ->
            LogRow(
                item = item,
                expanded = expanded == item.id,
                onToggle = {
                    val next = if (expanded == item.id) null else item.id
                    expanded = next
                    if (next != null) {
                        scope.launch {
                            val start = if (filterOpen) 8 else 4
                            list.animateScrollToItem(start + idx)
                        }
                    }
                },
                onEvent = ::applyFromEntry,
            )
        }
    }

    if (copyOpen) {
        val max = state.rows.size.coerceAtLeast(1)
        AlertDialog(
            onDismissRequest = { copyOpen = false },
            title = { Text("Copy logs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Entries: ${copyCount.toInt()}")
                    Slider(
                        value = copyCount,
                        onValueChange = { copyCount = it },
                        valueRange = 1f..max.toFloat(),
                        steps = (max - 2).coerceAtLeast(0),
                    )
                    Text("Min 1, max $max")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(rawLogs(state.rows.take(copyCount.toInt()))))
                        copied = true
                        copyOpen = false
                    },
                ) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { copyOpen = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ActiveFilters(state: LogsUiState, onEvent: (LogsEvent) -> Unit) {
    val chips = buildList {
        state.selectedFrom?.let { add("from:${DateTime.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" to LogsFilterKey.from) }
        state.selectedUntil?.let { add("until:${DateTime.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" to LogsFilterKey.until) }
        state.selectedUnit?.let { add("unit:${it.label}" to LogsFilterKey.unit) }
        state.selectedLevel?.let { add("level:${it.name.uppercase()}" to LogsFilterKey.level) }
        state.selectedProjectId?.let { id ->
            val name = state.facet.projects.firstOrNull { it.id == id }?.name ?: id
            add("project:$name" to LogsFilterKey.project)
        }
        state.selectedSessionId?.let { id ->
            val title = state.facet.sessions.firstOrNull { it.id == id }?.title ?: id
            add("session:$title" to LogsFilterKey.session)
        }
        state.selectedEvent?.let { add("event:$it" to LogsFilterKey.event) }
    }
    if (chips.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Active Filters", style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            chips.forEach {
                AssistChip(
                    onClick = { onEvent(LogsEvent.FilterRemoved(it.second)) },
                    label = { Text(it.first) },
                )
            }
            AssistChip(
                onClick = { onEvent(LogsEvent.FiltersResetRequested) },
                label = { Text("clear-all") },
            )
        }
    }

}

@Composable
private fun TimeFilterRow(
    from: Long?,
    until: Long?,
    onFrom: (Long?) -> Unit,
    onUntil: (Long?) -> Unit,
) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Time", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = {
                    pickDateTime(context, from ?: now) { onFrom(it) }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(from?.let { "From ${DateTime.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" } ?: "From")
            }
            OutlinedButton(
                onClick = {
                    pickDateTime(context, until ?: now) { onUntil(it) }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(until?.let { "Until ${DateTime.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" } ?: "Until")
            }
        }
    }
}

@Composable
private fun ValueFilterRow(
    title: String,
    selected: String?,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
) {
    if (options.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            AssistChip(
                onClick = { onSelect(null) },
                label = { Text("All") },
            )
            options.forEach {
                AssistChip(
                    onClick = { onSelect(it.first) },
                    label = {
                        Text(
                            text = if (selected == it.first) "${it.second} *" else it.second,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
        }
    }
}

@Composable
private fun LogRow(
    item: LogEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEvent: (LogsEvent) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = item.level.color()),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${item.level.name.uppercase()}  ${item.unit.label}  ${item.event}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = item.message,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${item.tag} - ${Time.format(Instant.ofEpochMilli(item.createdAt).atZone(ZoneId.systemDefault()))}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.context.isNotEmpty()) {
                Text(
                    text = item.context.entries.joinToString(" | ") { "${it.key}=${it.value}" },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (!item.throwable.isNullOrBlank()) {
                Text(
                    text = item.throwable,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (expanded) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    item.projectId?.let {
                        AssistChip(
                            onClick = { onEvent(LogsEvent.FilterAppliedFromEntry(LogsFilterKey.project, it)) },
                            label = { Text("project") },
                        )
                    }
                    item.sessionId?.let {
                        AssistChip(
                            onClick = { onEvent(LogsEvent.FilterAppliedFromEntry(LogsFilterKey.session, it)) },
                            label = { Text("session") },
                        )
                    }
                    AssistChip(
                        onClick = { onEvent(LogsEvent.FilterAppliedFromEntry(LogsFilterKey.event, item.event)) },
                        label = { Text("event") },
                    )
                    AssistChip(
                        onClick = { onEvent(LogsEvent.FilterAppliedFromEntry(LogsFilterKey.unit, item.unit.key)) },
                        label = { Text("unit") },
                    )
                    AssistChip(
                        onClick = { onEvent(LogsEvent.FromChanged(item.createdAt)) },
                        label = { Text("from") },
                    )
                    AssistChip(
                        onClick = { onEvent(LogsEvent.UntilChanged(item.createdAt)) },
                        label = { Text("until") },
                    )
                }
            }
        }
    }
}

@Composable
private fun LogLevel.color() = when (this) {
    LogLevel.debug -> MaterialTheme.colorScheme.surfaceVariant
    LogLevel.info -> MaterialTheme.colorScheme.surface
    LogLevel.warn -> MaterialTheme.colorScheme.tertiaryContainer
    LogLevel.error -> MaterialTheme.colorScheme.errorContainer
}

private fun rawLogs(rows: List<LogEntry>): String {
    return rows.joinToString("\n") {
        val stamp = Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toString()
        val context = it.context.entries.joinToString(",") { entry -> "\"${entry.key}\":\"${entry.value}\"" }
        "{" +
            "\"id\":${it.id}," +
            "\"createdAt\":${it.createdAt}," +
            "\"timestamp\":\"$stamp\"," +
            "\"level\":\"${it.level.key}\"," +
            "\"unit\":\"${it.unit.key}\"," +
            "\"tag\":\"${it.tag}\"," +
            "\"event\":\"${it.event}\"," +
            "\"projectId\":\"${it.projectId.orEmpty()}\"," +
            "\"projectName\":\"${it.projectName.orEmpty()}\"," +
            "\"sessionId\":\"${it.sessionId.orEmpty()}\"," +
            "\"sessionTitle\":\"${it.sessionTitle.orEmpty()}\"," +
            "\"message\":\"${it.message}\"," +
            "\"context\":{$context}," +
            "\"throwable\":\"${it.throwable.orEmpty()}\"" +
            "}"
    }
}

private fun pickDateTime(context: android.content.Context, initial: Long, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        context,
        { _, y, m, d ->
            TimePickerDialog(
                context,
                { _, h, min ->
                    val value = Calendar.getInstance().apply {
                        set(Calendar.YEAR, y)
                        set(Calendar.MONTH, m)
                        set(Calendar.DAY_OF_MONTH, d)
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, min)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPick(value.timeInMillis)
                },
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                true,
            ).show()
        },
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH),
        c.get(Calendar.DAY_OF_MONTH),
    ).show()
}

private val Time = DateTimeFormatter.ofPattern("MMM d HH:mm:ss")
private val DateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
