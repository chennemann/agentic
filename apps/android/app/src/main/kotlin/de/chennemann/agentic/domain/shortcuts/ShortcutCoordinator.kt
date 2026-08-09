package de.chennemann.agentic.domain.shortcuts

import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentSelector
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ThreadActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class ShortcutFailure {
    INVALID_ROUTE,
    ENVIRONMENT_MISSING,
    PROJECT_REQUIRED,
    TARGET_MISSING,
}

interface ShortcutCoordinator {
    val failures: Flow<ShortcutFailure>
}

class DefaultShortcutCoordinator(
    private val environments: EnvironmentRepository,
    private val orchestration: OrchestrationRepository,
    private val environmentSelector: EnvironmentSelector,
    private val threads: ThreadActions,
    publisher: DynamicShortcutPublisher,
    routes: ShortcutRouteInbox,
    scope: CoroutineScope,
) : ShortcutCoordinator {
    private val failureEvents = Channel<ShortcutFailure>(Channel.BUFFERED)
    override val failures = failureEvents.receiveAsFlow()

    init {
        scope.launch {
            combine(
                environments.activeEnvironment,
                orchestration.clientConfig,
                orchestration.shell,
                orchestration.selectedProjectId,
            ) { active, config, shell, selectedProjectId ->
                if (active == null || config.value?.environment?.environmentId != active.id ||
                    shell.value == null || !shell.synchronized
                ) emptyList() else {
                    val projects = shell.value.projects.associateBy { it.id }
                    val selected = selectedProjectId?.takeIf(projects::containsKey)
                    listOf(DynamicShortcutSpec("new-task:${active.id}", "New task", ShortcutRoute.NewTask(active.id, selected))) +
                        shell.value.threads
                            .filter {
                                it.archivedAt == null && it.settledAt == null && it.snoozedUntil == null &&
                                    projects.containsKey(it.projectId)
                            }
                            .sortedByDescending { it.updatedAt }
                            .take(3)
                            .map {
                                DynamicShortcutSpec(
                                    "thread:${active.id}:${it.id}",
                                    it.title,
                                    ShortcutRoute.Thread(active.id, it.projectId, it.id),
                                )
                            }
                }
            }.collect(publisher::publish)
        }
        scope.launch { routes.routes.collect(::route) }
    }

    private suspend fun route(result: ShortcutParseResult) {
        if (result is ShortcutParseResult.Invalid) return fail(ShortcutFailure.INVALID_ROUTE)
        val route = (result as ShortcutParseResult.Valid).route
        if (environments.environments.value.none { it.id == route.environmentId }) {
            return fail(ShortcutFailure.ENVIRONMENT_MISSING)
        }
        if (environments.activeEnvironment.value?.id != route.environmentId) {
            environmentSelector.select(route.environmentId)
            orchestration.clientConfig.first { it.value?.environment?.environmentId == route.environmentId }
            orchestration.shell.first { it.synchronized && it.value != null }
        }
        val shell = orchestration.shell.value.value
        when (route) {
            is ShortcutRoute.NewTask -> {
                val projectId = route.projectId
                if (projectId == null || shell?.projects?.none { it.id == projectId } != false) {
                    return fail(ShortcutFailure.PROJECT_REQUIRED)
                }
                threads.selectProject(projectId)
                threads.selectThread(null)
            }
            is ShortcutRoute.Thread -> {
                val valid = shell?.projects?.any { it.id == route.projectId } == true &&
                    shell.threads.any { it.id == route.threadId && it.projectId == route.projectId }
                if (!valid) return fail(ShortcutFailure.TARGET_MISSING)
                threads.selectProject(route.projectId)
                threads.selectThread(route.threadId)
            }
        }
    }

    private fun fail(failure: ShortcutFailure) {
        failureEvents.trySend(failure)
    }
}
