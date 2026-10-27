package de.chennemann.agentic.data.t3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class GuestShareRpcFailureTest {
    @Test
    fun `guest link creation failure is explained by its reason`() {
        val cause = Json.parseToJsonElement(
            """[{"_tag":"Fail","error":{"_tag":"GuestShareCreateError","reason":"connect_unavailable"}}]""",
        ).jsonArray

        val failure = assertInstanceOf(T3TransportException.Rpc::class.java, decodeRpcFailure(cause))

        assertEquals("Guest links need a running T3 Connect tunnel.", failure.message)
    }

    @Test
    fun `missing access permission stays an authorization failure`() {
        val cause = Json.parseToJsonElement(
            """[{"_tag":"Fail","error":{"_tag":"EnvironmentAuthorizationError","requiredScope":"access:write"}}]""",
        ).jsonArray

        val failure = assertInstanceOf(T3TransportException.Authorization::class.java, decodeRpcFailure(cause))

        assertEquals("access:write", failure.requiredScope)
    }
}
