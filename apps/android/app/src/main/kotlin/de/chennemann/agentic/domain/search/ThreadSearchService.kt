package de.chennemann.agentic.domain.search

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.ThreadSearchRpcClient
import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.connection.ConnectionState
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.t3.contract.THREAD_SEARCH_MAX_LIMIT
import de.chennemann.agentic.t3.contract.THREAD_SEARCH_QUERY_MAX_LENGTH
import de.chennemann.agentic.t3.contract.THREAD_SEARCH_QUERY_MIN_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

enum class ThreadMessageAuthor { USER, AGENT }

data class ThreadMessageMatch(
    val threadId: String,
    val projectId: String,
    val author: ThreadMessageAuthor,
    val snippet: String,
    val messageCreatedAt: String?,
    val messageId: String?,
)

sealed interface ThreadMessageSearchResult {
    data class Matches(val matches: List<ThreadMessageMatch>) : ThreadMessageSearchResult

    /** Message search could not run; title matches from the local shell still apply. */
    data class Unavailable(val message: String) : ThreadMessageSearchResult
}

interface ThreadMessageSearch {
    /** The last query searched, so reopening search after visiting a thread restores it. */
    val lastQuery: StateFlow<String>

    fun remember(query: String)

    suspend fun search(query: String): ThreadMessageSearchResult
}

/** Folded, whitespace-collapsed query cut to the server's limit; empty when nothing matches. */
fun normalizeThreadSearchQuery(query: String): String =
    query.trim().take(THREAD_SEARCH_QUERY_MAX_LENGTH).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

/** The server skips message search unless a word has three or more letters. */
fun canSearchThreadMessages(query: String): Boolean {
    val normalized = normalizeThreadSearchQuery(query)
    return normalized.length >= THREAD_SEARCH_QUERY_MIN_LENGTH &&
        normalized.split(' ').any { it.codePointCount(0, it.length) >= 3 }
}

class ThreadSearchService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val connection: ConnectionController,
    private val rpc: ThreadSearchRpcClient,
    private val timeoutMillis: Long = SearchTimeoutMillis,
) : ThreadMessageSearch {
    private val mutableLastQuery = MutableStateFlow("")
    override val lastQuery: StateFlow<String> = mutableLastQuery.asStateFlow()

    override fun remember(query: String) {
        mutableLastQuery.value = query
    }

    override suspend fun search(query: String): ThreadMessageSearchResult {
        val normalized = normalizeThreadSearchQuery(query)
        if (!canSearchThreadMessages(normalized)) return ThreadMessageSearchResult.Matches(emptyList())
        val environment = environments.activeEnvironment.value
            ?: return ThreadMessageSearchResult.Unavailable("Select an environment to search messages.")
        // An unreachable environment must not hold up title matches.
        if (connection.state.value !is ConnectionState.Live && connection.state.value !is ConnectionState.Synchronizing) {
            return ThreadMessageSearchResult.Unavailable(OfflineMessage)
        }
        val token = credentials.read(environment.id)
            ?: return ThreadMessageSearchResult.Unavailable("Pair this environment again to search messages.")
        return try {
            val result = withTimeout(timeoutMillis) {
                rpc.searchThreads(environment.baseUrl, token, normalized, THREAD_SEARCH_MAX_LIMIT)
            }
            if (environments.activeEnvironment.value?.id != environment.id) {
                return ThreadMessageSearchResult.Matches(emptyList())
            }
            ThreadMessageSearchResult.Matches(
                result.matches.map {
                    ThreadMessageMatch(
                        threadId = it.threadId,
                        projectId = it.projectId,
                        author = if (it.source == "user") ThreadMessageAuthor.USER else ThreadMessageAuthor.AGENT,
                        snippet = it.snippet,
                        messageCreatedAt = it.messageCreatedAt,
                        messageId = it.messageId,
                    )
                },
            )
        } catch (_: TimeoutCancellationException) {
            ThreadMessageSearchResult.Unavailable(OfflineMessage)
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            ThreadMessageSearchResult.Unavailable("Message search failed. Showing title matches only.")
        }
    }

    private companion object {
        const val SearchTimeoutMillis = 8_000L
        const val OfflineMessage = "The environment is unreachable. Showing title matches only."
    }
}
