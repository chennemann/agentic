package de.chennemann.agentic.data.t3

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface GuestShareRpcClient {
    suspend fun guestShareCall(baseUrl: String, bearerToken: String, method: String, input: JsonObject): JsonElement
}
