package de.chennemann.agentic.t3.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val ORCHESTRATION_SEARCH_THREADS_METHOD = "orchestration.searchThreads"

/** Longer queries are cut to this before they are sent. */
const val THREAD_SEARCH_QUERY_MAX_LENGTH = 200
const val THREAD_SEARCH_QUERY_MIN_LENGTH = 2
const val THREAD_SEARCH_MAX_LIMIT = 50

/** A message hit from the server's indexed thread search. Additive fields stay optional. */
@Serializable
data class OrchestrationThreadSearchMatch(
    val threadId: String,
    val projectId: String,
    val source: String,
    val snippet: String,
    val messageCreatedAt: String? = null,
    val quality: Double? = null,
    val hits: Int? = null,
    val messageId: String? = null
)

@Serializable
data class OrchestrationSearchThreadsResult(
    val matches: List<OrchestrationThreadSearchMatch> = emptyList(),
    val snapshotSequence: Long? = null
)

fun orchestrationSearchThreadsInput(query: String, limit: Int): JsonObject = buildJsonObject {
    put("query", query.trim().take(THREAD_SEARCH_QUERY_MAX_LENGTH).trim())
    put("limit", limit.coerceIn(1, THREAD_SEARCH_MAX_LIMIT))
}
