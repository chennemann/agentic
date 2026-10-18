package de.chennemann.agentic.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.orchestration.WorkspaceFileListing
import de.chennemann.agentic.domain.orchestration.WorkspaceFilesBrowser
import de.chennemann.agentic.domain.orchestration.WorkspaceFilePreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkspaceFilesUiState(
    val listing: WorkspaceFileListing? = null,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val selectedPath: String? = null,
    val selectedLine: Int? = null,
    val preview: WorkspaceFilePreview? = null,
    val previewLoading: Boolean = false,
    val previewErrorMessage: String? = null,
)

class WorkspaceFilesViewModel(private val files: WorkspaceFilesBrowser) : ViewModel() {
    private val mutableState = MutableStateFlow(WorkspaceFilesUiState())
    val state: StateFlow<WorkspaceFilesUiState> = mutableState.asStateFlow()
    private var threadId: String? = null
    private var shownLink: Triple<String, String, Int?>? = null

    fun load(threadId: String) {
        if (this.threadId == threadId && mutableState.value.listing != null) return
        this.threadId = threadId
        refresh()
    }

    /** Opens one file without listing the workspace, as when following a link from chat. */
    fun show(threadId: String, path: String, line: Int?) {
        val link = Triple(threadId, path, line)
        if (shownLink == link) return
        shownLink = link
        this.threadId = threadId
        open(path, line)
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

    fun open(path: String, line: Int? = null) {
        val currentThreadId = threadId ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                selectedPath = path,
                selectedLine = line,
                previewLoading = true,
                previewErrorMessage = null,
                preview = mutableState.value.preview?.takeIf { it.path == path },
            )
            runCatching { files.preview(currentThreadId, path) }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(preview = it, selectedPath = it.path, previewLoading = false)
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        previewLoading = false,
                        previewErrorMessage = it.message ?: "This file could not be previewed.",
                    )
                }
        }
    }

    fun closePreview() {
        mutableState.value = mutableState.value.copy(
            selectedPath = null,
            selectedLine = null,
            preview = null,
            previewLoading = false,
            previewErrorMessage = null,
        )
    }
}
