package de.chennemann.agentic.ui.chat.workflow

import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectActions
import de.chennemann.agentic.domain.orchestration.ThreadActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProjectWorkflowState(
    val creationVisible: Boolean = false,
    val creationSource: String = "",
    val creationSaving: Boolean = false,
    val creationError: String? = null,
    val renameId: String? = null,
    val renamePreviousTitle: String = "",
    val renameTitle: String = "",
    val renameSaving: Boolean = false,
    val renameError: String? = null,
    val removalId: String? = null,
    val removalTitle: String = "",
    val removalSaving: Boolean = false,
    val removalError: String? = null,
    val selectedProjectId: String? = null,
)

sealed interface ProjectWorkflowIntent {
    data object RequestCreation : ProjectWorkflowIntent
    data class ChangeCreationSource(val value: String) : ProjectWorkflowIntent
    data object DismissCreation : ProjectWorkflowIntent
    data object ConfirmCreation : ProjectWorkflowIntent
    data class RequestRename(val projectId: String) : ProjectWorkflowIntent
    data class ChangeRenameTitle(val value: String) : ProjectWorkflowIntent
    data object DismissRename : ProjectWorkflowIntent
    data object ConfirmRename : ProjectWorkflowIntent
    data class RequestRemoval(val projectId: String) : ProjectWorkflowIntent
    data object DismissRemoval : ProjectWorkflowIntent
    data object ConfirmRemoval : ProjectWorkflowIntent
}

interface ProjectWorkflow {
    val state: StateFlow<ProjectWorkflowState>
    suspend fun accept(intent: ProjectWorkflowIntent)
}

class DefaultProjectWorkflow(
    private val repository: OrchestrationRepository,
    private val projects: ProjectActions,
    private val threads: ThreadActions,
) : ProjectWorkflow {
    private val mutableState = MutableStateFlow(ProjectWorkflowState())
    override val state = mutableState.asStateFlow()

    override suspend fun accept(intent: ProjectWorkflowIntent) {
        when (intent) {
            ProjectWorkflowIntent.RequestCreation -> update { copy(creationVisible = true, creationError = null) }
            is ProjectWorkflowIntent.ChangeCreationSource -> update { copy(creationSource = intent.value, creationError = null) }
            ProjectWorkflowIntent.DismissCreation -> updateUnless(state.value.creationSaving) {
                copy(creationVisible = false, creationSource = "", creationError = null)
            }
            ProjectWorkflowIntent.ConfirmCreation -> runSaving(
                start = { copy(creationSaving = true, creationError = null) },
                operation = { projects.create(state.value.creationSource) },
                success = { id ->
                    threads.selectProject(id)
                    copy(
                        creationVisible = false,
                        creationSource = "",
                        creationSaving = false,
                        selectedProjectId = id,
                    )
                },
                failure = { copy(creationSaving = false, creationError = it.message ?: "Project creation failed.") },
            )
            is ProjectWorkflowIntent.RequestRename -> {
                val project = repository.shell.value.value?.projects?.firstOrNull { it.id == intent.projectId } ?: return
                update { copy(renameId = project.id, renamePreviousTitle = project.title, renameTitle = project.title, renameError = null) }
            }
            is ProjectWorkflowIntent.ChangeRenameTitle -> update { copy(renameTitle = intent.value, renameError = null) }
            ProjectWorkflowIntent.DismissRename -> updateUnless(state.value.renameSaving) {
                copy(renameId = null, renamePreviousTitle = "", renameTitle = "", renameError = null)
            }
            ProjectWorkflowIntent.ConfirmRename -> {
                val id = state.value.renameId ?: return
                runSaving(
                    start = { copy(renameSaving = true, renameError = null) },
                    operation = { projects.rename(id, state.value.renameTitle) },
                    success = { copy(renameId = null, renamePreviousTitle = "", renameTitle = "", renameSaving = false) },
                    failure = { copy(renameSaving = false, renameError = it.message ?: "Project rename failed.") },
                )
            }
            is ProjectWorkflowIntent.RequestRemoval -> {
                val project = repository.shell.value.value?.projects?.firstOrNull { it.id == intent.projectId } ?: return
                update { copy(removalId = project.id, removalTitle = project.title, removalError = null) }
            }
            ProjectWorkflowIntent.DismissRemoval -> updateUnless(state.value.removalSaving) {
                copy(removalId = null, removalTitle = "", removalError = null)
            }
            ProjectWorkflowIntent.ConfirmRemoval -> {
                val id = state.value.removalId ?: return
                runSaving(
                    start = { copy(removalSaving = true, removalError = null) },
                    operation = { projects.remove(id) },
                    success = { fallback ->
                        threads.selectProject(fallback)
                        copy(removalId = null, removalTitle = "", removalSaving = false, selectedProjectId = fallback)
                    },
                    failure = { copy(removalSaving = false, removalError = it.message ?: "Project removal failed.") },
                )
            }
        }
    }

    private inline fun update(transform: ProjectWorkflowState.() -> ProjectWorkflowState) {
        mutableState.value = mutableState.value.transform()
    }

    private inline fun updateUnless(blocked: Boolean, transform: ProjectWorkflowState.() -> ProjectWorkflowState) {
        if (!blocked) update(transform)
    }

    private suspend fun <T> runSaving(
        start: ProjectWorkflowState.() -> ProjectWorkflowState,
        operation: suspend () -> T,
        success: suspend ProjectWorkflowState.(T) -> ProjectWorkflowState,
        failure: ProjectWorkflowState.(Exception) -> ProjectWorkflowState,
    ) {
        update(start)
        try {
            val result = operation()
            mutableState.value = mutableState.value.success(result)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            mutableState.value = mutableState.value.failure(cause)
        }
    }
}
