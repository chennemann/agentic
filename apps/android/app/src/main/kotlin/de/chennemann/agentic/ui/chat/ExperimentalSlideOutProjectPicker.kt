package de.chennemann.agentic.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * PROTOTYPE: three slide-out project-picker layouts hosted on the real chat screen.
 * Keep this isolated until one structure is selected and rewritten as production UI.
 */
@Composable
fun ExperimentalSlideOutProjectPicker(
    visible: Boolean,
    projects: List<ProjectPickerItemUi>,
    selectedProjectId: String?,
    onDismiss: () -> Unit,
    onProjectSelected: (String) -> Unit,
) {
    var variant by remember { mutableStateOf(ProjectPickerPrototypeVariant.LIST) }

    BackHandler(enabled = visible, onBack = onDismiss)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .clickable(onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { -it }),
        exit = slideOutHorizontally(targetOffsetX = { -it }),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = 12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Projects", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Prototype · ${variant.label}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Text("×", style = MaterialTheme.typography.headlineSmall)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when (variant) {
                        ProjectPickerPrototypeVariant.LIST -> ProjectListVariant(
                            projects = projects,
                            selectedProjectId = selectedProjectId,
                            onProjectSelected = onProjectSelected,
                        )

                        ProjectPickerPrototypeVariant.GRID -> ProjectGridVariant(
                            projects = projects,
                            selectedProjectId = selectedProjectId,
                            onProjectSelected = onProjectSelected,
                        )

                        ProjectPickerPrototypeVariant.INDEX -> ProjectIndexVariant(
                            projects = projects,
                            selectedProjectId = selectedProjectId,
                            onProjectSelected = onProjectSelected,
                        )
                    }
                }

                PrototypeVariantSwitcher(
                    variant = variant,
                    onVariantChanged = { variant = it },
                )
            }
        }
    }
}

@Composable
private fun ProjectListVariant(
    projects: List<ProjectPickerItemUi>,
    selectedProjectId: String?,
    onProjectSelected: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(projects, key = { it.id }) { project ->
            Surface(
                onClick = { onProjectSelected(project.id) },
                color = if (project.id == selectedProjectId) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(project.label, style = MaterialTheme.typography.titleMedium)
                    project.supportingText?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGridVariant(
    projects: List<ProjectPickerItemUi>,
    selectedProjectId: String?,
    onProjectSelected: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(projects, key = { it.id }) { project ->
            Card(
                onClick = { onProjectSelected(project.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (project.id == selectedProjectId) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        project.label.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(project.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ProjectIndexVariant(
    projects: List<ProjectPickerItemUi>,
    selectedProjectId: String?,
    onProjectSelected: (String) -> Unit,
) {
    val groups = projects.groupBy { it.label.firstOrNull()?.uppercase() ?: "#" }.toSortedMap()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        groups.forEach { (initial, groupedProjects) ->
            item("heading:$initial") {
                Text(
                    initial,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(groupedProjects, key = { it.id }) { project ->
                TextButton(
                    onClick = { onProjectSelected(project.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (project.id == selectedProjectId) "●  ${project.label}" else project.label,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PrototypeVariantSwitcher(
    variant: ProjectPickerPrototypeVariant,
    onVariantChanged: (ProjectPickerPrototypeVariant) -> Unit,
) {
    val variants = ProjectPickerPrototypeVariant.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            onVariantChanged(variants[(variant.ordinal - 1 + variants.size) % variants.size])
        }) { Text("←") }
        Text("${variant.name.first()} · ${variant.label}", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = {
            onVariantChanged(variants[(variant.ordinal + 1) % variants.size])
        }) { Text("→") }
    }
}

private enum class ProjectPickerPrototypeVariant(val label: String) {
    LIST("Compact list"),
    GRID("Card grid"),
    INDEX("Grouped index"),
}
