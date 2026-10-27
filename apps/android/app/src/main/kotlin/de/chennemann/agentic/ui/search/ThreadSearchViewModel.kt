package de.chennemann.agentic.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ThreadActions
import de.chennemann.agentic.domain.search.ThreadMessageMatch
import de.chennemann.agentic.domain.search.ThreadMessageSearch
import de.chennemann.agentic.domain.search.ThreadMessageSearchResult
import de.chennemann.agentic.domain.search.canSearchThreadMessages
import de.chennemann.agentic.domain.search.normalizeThreadSearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThreadSearchUiState(
    val query: String = "",
    val titleResults: List<ThreadSearchResultUi> = emptyList(),
    val messageResults: List<ThreadSearchResultUi> = emptyList(),
    val searchingMessages: Boolean = false,
    /** Why message results are missing, such as an unreachable environment. */
    val notice: String? = null,
    val opening: Boolean = false,
    val error: String? = null,
) {
    val idle: Boolean get() = normalizeThreadSearchQuery(query).isEmpty()
}

private data class MessageSearchState(
    val matches: List<ThreadMessageMatch> = emptyList(),
    val loading: Boolean = false,
    val notice: String? = null,
)

private data class SearchLocalState(val opening: Boolean = false, val error: String? = null)

/**
 * Searches thread titles in the cached shell immediately and message text on the server after a
 * short pause in typing. Results that were shown stay until newer ones replace them.
 */
class ThreadSearchViewModel(
    repository: OrchestrationRepository,
    private val search: ThreadMessageSearch,
    private val threads: ThreadActions,
    mappingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMillis: Long = SearchDebounceMillis,
) : ViewModel() {
    private val query = MutableStateFlow(search.lastQuery.value)
    private val messages = MutableStateFlow(MessageSearchState())
    private val local = MutableStateFlow(SearchLocalState())

    val state: StateFlow<ThreadSearchUiState> = combine(query, messages, repository.shell, local) { query, messages, shell, local ->
        val titles = rankThreadTitleMatches(query, shell.value)
        ThreadSearchUiState(
            query = query,
            titleResults = titles,
            messageResults = presentMessageMatches(query, messages.matches, shell.value, titles.map { it.threadId }.toSet()),
            searchingMessages = messages.loading,
            notice = messages.notice,
            opening = local.opening,
            error = local.error,
        )
    }.flowOn(mappingDispatcher).stateIn(viewModelScope, SharingStarted.Eagerly, ThreadSearchUiState(query = query.value))

    init {
        viewModelScope.launch {
            query.map(::normalizeThreadSearchQuery).distinctUntilChanged().collectLatest { normalized ->
                if (!canSearchThreadMessages(normalized)) {
                    messages.value = MessageSearchState()
                    return@collectLatest
                }
                messages.update { it.copy(loading = true) }
                delay(debounceMillis)
                messages.value = when (val result = search.search(normalized)) {
                    is ThreadMessageSearchResult.Matches -> MessageSearchState(result.matches)
                    is ThreadMessageSearchResult.Unavailable -> MessageSearchState(notice = result.message)
                }
            }
        }
    }

    fun changeQuery(value: String) {
        query.value = value
        search.remember(value)
        local.update { it.copy(error = null) }
    }

    fun open(threadId: String, onOpened: () -> Unit) {
        if (local.value.opening) return
        local.value = SearchLocalState(opening = true)
        viewModelScope.launch {
            try {
                threads.selectThread(threadId)
                local.value = SearchLocalState()
                onOpened()
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Exception) {
                local.value = SearchLocalState(error = "The thread could not be opened.")
            }
        }
    }

    private companion object {
        const val SearchDebounceMillis = 150L
    }
}
