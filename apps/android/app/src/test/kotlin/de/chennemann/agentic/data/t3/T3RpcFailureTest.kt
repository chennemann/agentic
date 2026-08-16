package de.chennemann.agentic.data.t3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class T3RpcFailureTest {
    @Test
    fun `missing terminal scope asks the user to pair again`() {
        val cause = Json.parseToJsonElement(
            """
            [
              {
                "_tag": "Fail",
                "error": {
                  "_tag": "EnvironmentAuthorizationError",
                  "message": "The authenticated token is missing required scope: terminal:operate.",
                  "requiredScope": "terminal:operate"
                }
              }
            ]
            """.trimIndent(),
        ).jsonArray

        val failure = decodeRpcFailure(cause)

        val authorization = assertInstanceOf(T3TransportException.Authorization::class.java, failure)
        assertEquals("terminal:operate", authorization.requiredScope)
        assertEquals(
            "Terminal access requires pairing this environment again.",
            authorization.message,
        )
    }

    @Test
    fun `safe rpc failure preserves its opaque trace id`() {
        val cause = Json.parseToJsonElement(
            """
            [{"_tag":"Fail","error":{"message":"Provider unavailable.","traceId":"trace-42"}}]
            """.trimIndent(),
        ).jsonArray

        val failure = assertInstanceOf(T3TransportException.Rpc::class.java, decodeRpcFailure(cause))

        assertEquals("Provider unavailable.", failure.message)
        assertEquals("trace-42", failure.traceId)
    }
}
