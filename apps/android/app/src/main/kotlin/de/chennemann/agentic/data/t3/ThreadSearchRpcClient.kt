package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.OrchestrationSearchThreadsResult

interface ThreadSearchRpcClient {
    suspend fun searchThreads(
        baseUrl: String,
        bearerToken: String,
        query: String,
        limit: Int,
    ): OrchestrationSearchThreadsResult
}
