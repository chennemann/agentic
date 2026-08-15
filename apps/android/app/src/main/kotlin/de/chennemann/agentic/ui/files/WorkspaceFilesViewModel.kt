package de.chennemann.agentic.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.orchestration.WorkspaceFileListing
import de.chennemann.agentic.domain.orchestration.WorkspaceFilesBrowser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkspaceFilesUiState(
    val listing: WorkspaceFileListing? = null,
    val loading: Boolean = false,
    val errorMessage: String? = null,
)

class WorkspaceFilesViewModel(private val files: WorkspaceFilesBrowser) : ViewModel() {
    private val mutableState = MutableStateFlow(WorkspaceFilesUiState())
    val state: StateFlow<WorkspaceFilesUiState> = mutableState.asStateFlow()
    private var threadId: String? = null

    fun load(threadId: String) {
        if (this.threadId == threadId && mutableState.value.listing != null) return
        this.threadId = threadId
        refresh()
    }

    fun refresh() {
        val currentThreadId = threadId ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, errorMessage = null)
            runCatching { files.list(currentThreadId) }
                .onSuccess { mutableState.value = WorkspaceFilesUiState(listing = it) }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        errorMessage = it.message ?: "Files are unavailable.",
                    )
                }
        }
    }
}
