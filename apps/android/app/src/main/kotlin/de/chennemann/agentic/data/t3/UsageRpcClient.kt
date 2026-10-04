package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.ServerConfigStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface UsageRpcClient {
    suspend fun usageCall(baseUrl: String, bearerToken: String, method: String, input: JsonObject): JsonElement
    fun usageConfigStream(baseUrl: String, bearerToken: String): Flow<ServerConfigStreamEvent>
}
