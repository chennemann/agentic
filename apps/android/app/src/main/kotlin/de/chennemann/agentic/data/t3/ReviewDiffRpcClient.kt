package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.ThreadTurnDiff

interface ReviewDiffRpcClient {
    suspend fun turnDiff(
        baseUrl: String,
        bearerToken: String,
        threadId: String,
        fromTurnCount: Int,
        toTurnCount: Int
    ): ThreadTurnDiff

    suspend fun fullThreadDiff(baseUrl: String, bearerToken: String, threadId: String, toTurnCount: Int): ThreadTurnDiff
}
