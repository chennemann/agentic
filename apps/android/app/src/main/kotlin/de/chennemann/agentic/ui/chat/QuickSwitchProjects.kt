package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.domain.projects.LocalProjectInfo
import de.chennemann.agentic.domain.session.ProjectState

internal fun quickSwitchProjects(
    localProjects: List<LocalProjectInfo>,
    sessionProjects: List<ProjectState>,
): List<ProjectState> {
    val legacyByWorktree = sessionProjects.groupBy { quickSwitchWorkspaceId(it.worktree) }
    val local = localProjects.map { project ->
        val worktree = quickSwitchWorkspaceId(project.path)
        val legacy = legacyByWorktree[worktree].orEmpty()
        ProjectState(
            id = project.id,
            worktree = worktree,
            name = project.name.trim().ifBlank { quickSwitchFolderName(project.path) },
            sandboxes = legacy
                .flatMap { it.sandboxes }
                .map(::quickSwitchWorkspaceId)
                .filter { it.isNotBlank() && it != worktree }
                .distinct(),
            favorite = project.pinned,
        )
    }
    val known = local.map { quickSwitchWorkspaceId(it.worktree) }.toSet()
    val legacyOnly = sessionProjects
        .groupBy { quickSwitchWorkspaceId(it.worktree) }
        .filterKeys { !known.contains(it) }
        .map { (worktree, values) ->
            val preferred = values.firstOrNull { it.name.isNotBlank() }
            ProjectState(
                id = values.firstOrNull { it.id.isNotBlank() }?.id ?: "project:$worktree",
                worktree = worktree,
                name = preferred?.name?.trim().orEmpty().ifBlank { quickSwitchFolderName(worktree) },
                sandboxes = values
                    .flatMap { it.sandboxes }
                    .map(::quickSwitchWorkspaceId)
                    .filter { it.isNotBlank() && it != worktree }
                    .distinct(),
                favorite = values.any { it.favorite },
            )
        }

    return (local + legacyOnly).sortedWith(
        compareByDescending<ProjectState> { it.favorite }
            .thenBy { it.name.lowercase() }
            .thenBy { quickSwitchWorkspaceId(it.worktree).lowercase() },
    )
}

private fun quickSwitchFolderName(path: String): String {
    val value = path.trim().trimEnd('/', '\\')
    if (value.isBlank()) return path
    val index = maxOf(value.lastIndexOf('/'), value.lastIndexOf('\\'))
    if (index < 0) return value
    val name = value.substring(index + 1)
    if (name.isBlank()) return value
    return name
}

private fun quickSwitchWorkspaceId(path: String): String {
    return path.trimEnd('/', '\\')
}
