package de.chennemann.agentic.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.orchestration.ReviewChanges
import de.chennemann.agentic.domain.orchestration.ReviewSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReviewDiffUiState(
    val title: String = "Changes",
    val sections: List<ReviewSection> = emptyList(),
    val selectedId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val document: ReviewDocument = ReviewDocument(),
    val revision: Long = 0
)

class ReviewDiffViewModel(
    private val changes: ReviewChanges,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReviewDiffUiState())
    val state = mutableState.asStateFlow()
    private var threadId: String? = null
    private var job: Job? = null
    private var generation = 0L

    fun load(threadId: String) {
        if (this.threadId == threadId) return
        this.threadId = threadId
        mutableState.value = ReviewDiffUiState()
        request(null)
    }

    fun select(id: String) {
        if (id != mutableState.value.selectedId || mutableState.value.error != null) request(id)
    }

    fun refresh() = request(mutableState.value.selectedId)

    private fun request(sectionId: String?) {
        val thread = threadId ?: return
        job?.cancel()
        val request = ++generation
        // A previous range must not remain visible under a newly selected range's title.
        mutableState.value =
            mutableState.value.copy(loading = true, error = null, selectedId = sectionId, document = ReviewDocument())
        job = viewModelScope.launch {
            try {
                val snapshot = changes.load(thread, sectionId)
                val document = withContext(parsingDispatcher) { parseReviewDiff(snapshot.diff) }
                if (request == generation) {
                    mutableState.value =
                        ReviewDiffUiState(
                            snapshot.title,
                            snapshot.sections,
                            snapshot.selectedId,
                            document = document,
                            revision = request
                        )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Exception) {
                if (request == generation) {
                    mutableState.value =
                        mutableState.value.copy(
                            loading = false,
                            error = "Changes could not be loaded. Check the connection and try again."
                        )
                }
            }
        }
    }
}
