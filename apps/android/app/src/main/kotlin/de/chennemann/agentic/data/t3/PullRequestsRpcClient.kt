package de.chennemann.agentic.data.t3

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface PullRequestsRpcClient {
    suspend fun pullRequestCall(baseUrl: String, token: String, method: String, input: JsonObject): JsonElement
    suspend fun pullRequestDiff(baseUrl: String, token: String, input: JsonObject): JsonElement
    fun pullRequestRefreshes(baseUrl: String, token: String): Flow<Long>
}
