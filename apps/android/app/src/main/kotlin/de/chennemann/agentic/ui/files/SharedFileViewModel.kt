package de.chennemann.agentic.ui.files

import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.attachments.DelimitedTable
import de.chennemann.agentic.domain.attachments.SharedFile
import de.chennemann.agentic.domain.attachments.SharedFileKind
import de.chennemann.agentic.domain.attachments.SharedFileLoader
import de.chennemann.agentic.domain.attachments.SharedFileSource
import de.chennemann.agentic.domain.attachments.SharedText
import de.chennemann.agentic.domain.attachments.parseDelimited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where conversation content opens shared files; [threadId] resolves paths a message names. */
data class SharedFileHost(val threadId: String?, val open: (SharedFileSource) -> Unit)

val LocalSharedFiles = compositionLocalOf { SharedFileHost(null) {} }

sealed interface SharedFileContent {
    /** Rendered or played straight from the downloaded file. */
    data object Media : SharedFileContent

    data class Text(val text: SharedText) : SharedFileContent

    data class Markdown(val text: SharedText) : SharedFileContent

    data class Table(val table: DelimitedTable) : SharedFileContent

    data class Unsupported(val reason: String) : SharedFileContent
}

data class SharedFileUiState(
    val source: SharedFileSource? = null,
    val loading: Boolean = false,
    val file: SharedFile? = null,
    val content: SharedFileContent? = null,
    val errorMessage: String? = null,
)

class SharedFileViewModel(private val files: SharedFileLoader) : ViewModel() {
    private val mutableState = MutableStateFlow(SharedFileUiState())
    val state: StateFlow<SharedFileUiState> = mutableState.asStateFlow()

    fun load(source: SharedFileSource) {
        val current = mutableState.value
        if (current.source == source && (current.loading || current.content != null)) return
        mutableState.value = SharedFileUiState(source = source, loading = true)
        viewModelScope.launch {
            mutableState.value = try {
                val file = files.load(source)
                SharedFileUiState(source = source, file = file, content = content(file))
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                SharedFileUiState(source = source, errorMessage = cause.message ?: "The file could not be opened.")
            }
        }
    }

    fun retry() {
        val source = mutableState.value.source ?: return
        mutableState.value = SharedFileUiState()
        load(source)
    }

    private suspend fun content(file: SharedFile): SharedFileContent = when (file.kind) {
        SharedFileKind.Image, SharedFileKind.Svg, SharedFileKind.Pdf, SharedFileKind.Audio, SharedFileKind.Video ->
            SharedFileContent.Media
        SharedFileKind.Binary -> SharedFileContent.Unsupported(UnsupportedReason)
        SharedFileKind.Markdown -> text(file) { SharedFileContent.Markdown(it) }
        SharedFileKind.Delimited -> text(file) {
            val tabs = file.mimeType == "text/tab-separated-values" || file.source.name.endsWith(".tsv", ignoreCase = true)
            SharedFileContent.Table(parseDelimited(it.contents, if (tabs) '\t' else ','))
        }
        SharedFileKind.Html, SharedFileKind.Text, SharedFileKind.Unknown -> text(file) { SharedFileContent.Text(it) }
    }

    private suspend fun text(file: SharedFile, content: (SharedText) -> SharedFileContent): SharedFileContent = try {
        content(files.readText(file))
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: IllegalArgumentException) {
        SharedFileContent.Unsupported(UnsupportedReason)
    }

    private companion object {
        const val UnsupportedReason = "This file can't be previewed here. Open it in another app or share it."
    }
}

sealed interface SharedFileLoadUi {
    data object Loading : SharedFileLoadUi

    data class Loaded(val file: SharedFile) : SharedFileLoadUi

    data class Failed(val message: String) : SharedFileLoadUi
}

/** Downloads media that plays inside the conversation; one item plays at a time. */
class SharedMediaViewModel(private val files: SharedFileLoader) : ViewModel() {
    private val mutableFiles = MutableStateFlow<Map<SharedFileSource, SharedFileLoadUi>>(emptyMap())
    val loads: StateFlow<Map<SharedFileSource, SharedFileLoadUi>> = mutableFiles.asStateFlow()
    private val mutablePlaying = MutableStateFlow<SharedFileSource?>(null)
    val playing: StateFlow<SharedFileSource?> = mutablePlaying.asStateFlow()

    fun play(source: SharedFileSource) {
        mutablePlaying.value = source
        val current = mutableFiles.value[source]
        if (current != null && current !is SharedFileLoadUi.Failed) return
        mutableFiles.update { it + (source to SharedFileLoadUi.Loading) }
        viewModelScope.launch {
            val result = try {
                SharedFileLoadUi.Loaded(files.load(source))
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                if (mutablePlaying.value == source) mutablePlaying.value = null
                SharedFileLoadUi.Failed(cause.message ?: "The audio could not be loaded.")
            }
            mutableFiles.update { it + (source to result) }
        }
    }

    fun stop(source: SharedFileSource) {
        mutablePlaying.update { if (it == source) null else it }
    }
}
