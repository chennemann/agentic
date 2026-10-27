package de.chennemann.agentic.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Server settings worth changing from a phone, grouped like the web settings pages (page → group → setting).
 * New settings join a [ServerSettingsSection] as another row.
 */
@Composable
fun ServerSettingsScreen(
    state: ServerSettingsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSaveGlobalText: (String, String) -> Unit,
    onSaveProjectText: (String, String?) -> Unit
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Server settings", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onRefresh, enabled = !state.loading && !state.saving) { Text("Refresh") }
        }
        if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.environmentLabel.isNotEmpty()) {
                item {
                    Text(
                        "Saved on ${state.environmentLabel} and shared with every client connected to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            state.message?.let { message ->
                item { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
            state.threadTitleInstructions?.let { setting ->
                item(key = "threads") {
                    ServerSettingsSection("Threads", "Text generation") {
                        ScopedTextSetting(
                            setting = setting,
                            project = state.project?.takeIf { state.projectOverridesSupported },
                            enabled = state.editable,
                            onSaveGlobal = { onSaveGlobalText(setting.key, it) },
                            onSaveProject = { onSaveProjectText(setting.key, it) }
                        )
                    }
                }
            }
            if (!state.loading && state.threadTitleInstructions == null && state.environmentLabel.isNotEmpty()) {
                item { Text("This server does not offer settings that can be edited here yet. Update T3 Code to get them.") }
            }
        }
    }
}

@Composable
fun ServerSettingsSection(title: String, group: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                group?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                content()
            }
        }
    }
}

@Composable
private fun ScopedTextSetting(
    setting: ScopedTextSettingUi,
    project: ProjectScopeUi?,
    enabled: Boolean,
    onSaveGlobal: (String) -> Unit,
    onSaveProject: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(setting.title, style = MaterialTheme.typography.bodyLarge)
        Text(setting.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextSettingEditor(
            label = if (project == null) setting.title else "All projects",
            saved = setting.value.global,
            placeholder = setting.placeholder,
            enabled = enabled,
            onSave = onSaveGlobal
        )
        if (project != null) {
            val overridden = setting.value.projectOverride != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Custom for ${project.title}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (overridden) "This project uses its own instructions." else "This project uses the instructions for all projects.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = overridden,
                    enabled = enabled,
                    onCheckedChange = { custom -> onSaveProject(if (custom) setting.value.global else null) }
                )
            }
            if (overridden) {
                TextSettingEditor(
                    label = project.title,
                    saved = setting.value.projectOverride.orEmpty(),
                    placeholder = setting.placeholder,
                    enabled = enabled,
                    onSave = onSaveProject
                )
            }
        }
    }
}

@Composable
private fun TextSettingEditor(
    label: String,
    saved: String,
    placeholder: String,
    enabled: Boolean,
    onSave: (String) -> Unit
) {
    var draft by rememberSaveable(saved) { mutableStateOf(saved) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onSave(draft) }, enabled = enabled && draft.trim() != saved) { Text("Save") }
        if (saved.isNotEmpty()) TextButton(onClick = { onSave("") }, enabled = enabled) { Text("Clear") }
    }
}
