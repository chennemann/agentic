package de.chennemann.agentic.ui.chat.workflow

import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentSelector
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ThreadActions
import de.chennemann.agentic.domain.preferences.ComposerDraftRepository
import de.chennemann.agentic.domain.sharing.PendingSharedText
import de.chennemann.agentic.domain.sharing.SharedTextImportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SharedTextWorkflowState(
    val pending: PendingSharedText? = null,
    val environmentId: String? = null,
    val projectId: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

sealed interface SharedTextWorkflowIntent {
    data class SelectEnvironment(val environmentId: String) : SharedTextWorkflowIntent
    data class SelectProject(val projectId: String) : SharedTextWorkflowIntent
    data object Import : SharedTextWorkflowIntent
    data object Discard : SharedTextWorkflowIntent
    data object DismissError : SharedTextWorkflowIntent
}

interface SharedTextWorkflow {
    val state: StateFlow<SharedTextWorkflowState>
    fun start(scope: CoroutineScope)
    suspend fun accept(intent: SharedTextWorkflowIntent)
}

class DefaultSharedTextWorkflow(
    private val imports: SharedTextImportRepository,
    private val environments: EnvironmentRepository,
    private val environmentSelector: EnvironmentSelector,
    private val orchestration: OrchestrationRepository,
    private val threads: ThreadActions,
    private val drafts: ComposerDraftRepository,
) : SharedTextWorkflow {
    private val mutableState = MutableStateFlow(SharedTextWorkflowState())
    override val state = mutableState.asStateFlow()

    override fun start(scope: CoroutineScope) {
        scope.launch {
            combine(imports.pending, imports.error) { pending, error -> pending to error }.collect { (pending, error) ->
                mutableState.value = mutableState.value.copy(pending = pending, error = error ?: mutableState.value.error)
            }
        }
    }

    override suspend fun accept(intent: SharedTextWorkflowIntent) {
        when (intent) {
            is SharedTextWorkflowIntent.SelectEnvironment -> {
                if (environments.environments.value.none { it.id == intent.environmentId }) return
                environmentSelector.select(intent.environmentId)
                mutableState.value = state.value.copy(environmentId = intent.environmentId, projectId = null, error = null)
            }
            is SharedTextWorkflowIntent.SelectProject -> if (
                orchestration.shell.value.value?.projects?.any { it.id == intent.projectId } == true
            ) mutableState.value = state.value.copy(projectId = intent.projectId, error = null)
            SharedTextWorkflowIntent.Import -> import()
            SharedTextWorkflowIntent.Discard -> discard()
            SharedTextWorkflowIntent.DismissError -> {
                imports.clearError()
                mutableState.value = state.value.copy(error = null)
            }
        }
    }

    private suspend fun import() {
        if (state.value.saving) return
        val environmentId = state.value.environmentId ?: return
        val projectId = state.value.projectId ?: return
        val pending = state.value.pending ?: return
        if (environments.activeEnvironment.value?.id != environmentId ||
            orchestration.shell.value.value?.projects?.none { it.id == projectId } != false
        ) {
            mutableState.value = state.value.copy(error = "Select an available environment and project.")
            return
        }
        mutableState.value = state.value.copy(saving = true, error = null)
        try {
            val draftId = "new-project:$projectId"
            val existing = drafts.observe(environmentId, draftId).first()
            if (existing.isNotBlank() && existing != pending.text) {
                mutableState.value = state.value.copy(saving = false, error = "That project already has an unsent new-task draft.")
                return
            }
            if (existing.isEmpty()) drafts.setDraft(environmentId, draftId, pending.text)
            threads.selectProject(projectId)
            threads.selectThread(null)
            imports.markImported(pending.fingerprint)
            mutableState.value = state.value.copy(environmentId = null, projectId = null, saving = false, error = null)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            mutableState.value = state.value.copy(saving = false, error = cause.message ?: "Shared text import failed.")
        }
    }

    private suspend fun discard() {
        if (state.value.saving) return
        val fingerprint = state.value.pending?.fingerprint ?: return
        try {
            imports.discard(fingerprint)
            mutableState.value = state.value.copy(environmentId = null, projectId = null, error = null)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            mutableState.value = state.value.copy(error = cause.message ?: "Discard failed.")
        }
    }
}
