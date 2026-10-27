package de.chennemann.agentic.t3.contract

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsageAndConnectionContractTest {
    @Test
    fun `usage summary decodes new provider kinds and refreshing sources`() {
        val summary = T3Json.decodeFromString<UsageSummary>(
            """
            {"contractVersion":6,"readAt":"2026-10-27T10:00:00.000Z","timeZone":"Europe/Berlin",
             "sinceDay":"2026-10-21","untilDay":"2026-10-27",
             "buckets":[{"day":"2026-10-27","provider":"muse","model":"muse-1","sourcePath":"/home/me/.muse",
               "totals":{"uncachedInputTokens":10,"cachedInputTokens":5,"cacheCreationTokens":0,"outputTokens":7,"reasoningTokens":2},
               "costUsd":0.5,"cacheSavingsUsd":0.1,"categoryCostUsd":{"input":0.1,"cacheRead":0.0,"cacheWrite":0.0,"output":0.4},
               "costSource":"modelPriced","records":3,"unpricedRecords":0,"sessions":1}],
             "sources":[{"fingerprint":{"hostId":"host","provider":"muse","resolvedHomePath":"/home/me/.muse","volumeId":"1:2"},
               "status":"partial","scannedFiles":1,"skippedFiles":0,"malformedRecords":0,"distinctSessions":1,
               "message":null,"refreshing":true},
              {"fingerprint":{"hostId":"host","provider":"codex","resolvedHomePath":"/home/me/.codex","volumeId":"1:3"},
               "status":"ok","scannedFiles":2,"skippedFiles":0,"malformedRecords":0,"distinctSessions":4,"message":null}],
             "pricing":{"status":"cached","source":"litellm","fetchedAt":null,"knownModels":12},"scanDurationMs":42}
            """.trimIndent()
        )

        assertEquals("muse", summary.buckets.single().provider)
        assertEquals(22L, summary.buckets.single().totals.total)
        assertTrue(summary.sources[0].refreshing)
        assertFalse(summary.sources[1].refreshing)
        assertNull(summary.pricing.fetchedAt)
    }

    @Test
    fun `usage summary input asks for a fresh scan only when requested`() {
        val input = UsageSummaryInput("2026-10-21", "2026-10-27", "Europe/Berlin", "day")

        assertFalse("awaitRefresh" in T3Json.encodeToJsonElement(UsageSummaryInput.serializer(), input).jsonObject)
        assertEquals(
            "true",
            T3Json.encodeToJsonElement(UsageSummaryInput.serializer(), input.copy(awaitRefresh = true))
                .jsonObject.getValue("awaitRefresh").toString()
        )
    }

    @Test
    fun `server config carries the host's last event loop stall`() {
        val config = T3Json.decodeFromString<ServerConfig>(
            """
            {"environment":{"environmentId":"env","label":"Studio","platform":{"os":"darwin","arch":"arm64"},
               "serverVersion":"0.1.3","orchestrationProtocolVersion":2,"capabilities":{}},
             "auth":{"policy":"remote-reachable","bootstrapMethods":[],"sessionMethods":[],"sessionCookieName":"t3"},
             "providers":[],
             "lastEventLoopStall":{"detectedAt":"2026-10-27T10:00:00.000Z","durationMs":12400,"cpuCount":8}}
            """.trimIndent()
        )

        val stall = requireNotNull(config.lastEventLoopStall)
        assertEquals(12_400, stall.durationMs)
        assertEquals(8, stall.cpuCount)
        assertNull(stall.loadAverage)
    }

    @Test
    fun `incompatible protocol upgrade body decodes`() {
        val body = T3Json.decodeFromString<OrchestrationProtocolIncompatible>(
            """{"code":"orchestration_protocol_incompatible","message":"Update this client to one that supports orchestration protocol 2.","orchestrationProtocolVersion":2}"""
        )

        assertEquals("orchestration_protocol_incompatible", body.code)
        assertEquals(2, body.orchestrationProtocolVersion)
    }
}
