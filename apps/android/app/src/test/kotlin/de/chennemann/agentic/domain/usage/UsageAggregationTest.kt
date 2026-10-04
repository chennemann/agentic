package de.chennemann.agentic.domain.usage

import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.ConsumeResetResult
import de.chennemann.agentic.t3.contract.EnvironmentPlatform
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentCapabilities
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.ProviderAuth
import de.chennemann.agentic.t3.contract.ProviderInstance
import de.chennemann.agentic.t3.contract.ProviderUsageLimits
import de.chennemann.agentic.t3.contract.ResetCredits
import de.chennemann.agentic.t3.contract.ServerAuthDescriptor
import de.chennemann.agentic.t3.contract.ServerConfig
import de.chennemann.agentic.t3.contract.UsageBucket
import de.chennemann.agentic.t3.contract.UsageFingerprint
import de.chennemann.agentic.t3.contract.UsageLimitAccount
import de.chennemann.agentic.t3.contract.UsageLimitSource
import de.chennemann.agentic.t3.contract.UsagePricing
import de.chennemann.agentic.t3.contract.UsageSource
import de.chennemann.agentic.t3.contract.UsageSummary
import de.chennemann.agentic.t3.contract.UsageTokens
import de.chennemann.agentic.t3.contract.UsageWindow
import de.chennemann.agentic.ui.usage.parsePrice
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsageAggregationTest {
    private val input = usageWindow(7, Instant.parse("2026-10-04T12:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val fingerprint = UsageFingerprint("host", "custom", "/home/sessions", "1:2")
    private fun bucket(day: String = "2026-10-04", sourcePath: String? = "/home/sessions") = UsageBucket(
        day, "custom", "model",
        UsageTokens(
            100,
            20,
            30,
            40,
            15
        ),
        2.0, 1.0, "modelPriced", 1, 0, 1, sourcePath = sourcePath
    )
    private fun environment(
        id: String,
        status: String = "ok",
        readAt: String = "2026-10-04T12:00:00Z",
        buckets: List<UsageBucket> = listOf(bucket()),
        fp: UsageFingerprint = fingerprint,
        version: Int = 6
    ) = EnvironmentUsage(
        SavedEnvironment(id, id, "http://localhost", "linux", "x64", "1", false, null),
        UsageSummary(
            version, readAt, input.timeZone, input.sinceDay, input.untilDay, buckets,
            listOf(
                UsageSource(fp, status, 1, 0, 0, 2)
            ),
            UsagePricing("fresh", "rates", null, 1), 10
        )
    )

    @Test fun `reasoning is already included in output`() {
        assertEquals(190L, bucket().totals.total)
    }

    @Test fun `same physical transcript across environments counts once`() {
        val merged = mergeUsage(listOf(environment("a"), environment("b")), input)
        assertEquals(1, merged.buckets.size)
        assertEquals(2L, merged.sessions["custom"])
        assertEquals(1, merged.duplicates.size)
    }

    @Test fun `different filesystem identity retains both sources`() {
        val merged =
            mergeUsage(listOf(environment("a"), environment("b", fp = fingerprint.copy(volumeId = "3:4"))), input)
        assertEquals(2, merged.buckets.size)
        assertEquals(4L, merged.sessions["custom"])
    }

    @Test fun `complete scan wins overlap but newer partial contributes absent cells`() {
        val merged =
            mergeUsage(
                listOf(
                    environment("complete", readAt = "2026-10-04T10:00:00Z"),
                    environment("partial", "partial", buckets = listOf(bucket(), bucket("2026-10-03")))
                ),
                input
            )
        assertEquals(2, merged.buckets.size)
    }

    @Test fun `incompatible contracts and wrong date windows never enter totals`() {
        val wrong = environment("wrong").let { it.copy(summary = it.summary!!.copy(sinceDay = "2020-01-01")) }
        val merged = mergeUsage(listOf(environment("future", version = 7), wrong), input)
        assertTrue(merged.buckets.isEmpty())
        assertEquals(1, merged.mismatches.size)
    }

    @Test fun `rolling day is 24 real hours across daylight savings`() {
        val now = Instant.parse("2026-10-25T12:37:00Z")
        val window = usageWindow(1, now, ZoneId.of("Europe/Berlin"))
        assertEquals("2026-10-24T12:37:00Z", window.sinceTime)
        assertEquals(now.toString(), window.untilTime)
        assertEquals("hour", window.resolution)
    }

    @Test fun `reset target pins hub account and credit and routes native by instance`() {
        assertEquals("{\"instanceId\":\"instance\"}", ResetTarget("env", instanceId = "instance").input().toString())
        assertEquals(
            "{\"sourceId\":\"source\",\"accountId\":\"account\",\"creditId\":\"credit\"}",
            ResetTarget("env", sourceId = "source", accountId = "account", creditId = "credit").input().toString()
        )
    }

    @Test fun `price validation preserves optional cache versus free and rejects infinity`() {
        val price = parsePrice(listOf("1", "2", "", "0"))!!
        assertNull(price.cacheReadCostPerMillionTokens)
        assertEquals(0.0, price.cacheWriteCostPerMillionTokens)
        assertNull(parsePrice(listOf("-1", "2", "", "")))
        assertNull(parsePrice(listOf("1e999", "2", "", "")))
    }

    @Test fun `reset outcomes retain warning and distinguish already redeemed`() {
        assertEquals(
            "That credit was already redeemed. Cooldown unchanged",
            resetOutcome(ConsumeResetResult("alreadyRedeemed", "Cooldown unchanged"))
        )
    }

    @Test fun `native and hub copies share quota and redeem through hub with pinned credit`() {
        val old =
            ProviderUsageLimits(
                "2026-10-04T10:00:00Z",
                listOf(UsageWindow("weekly", "weekly", "Weekly", 80.0)),
                ResetCredits(1)
            )
        val fresh = old.copy(
            checkedAt = "2026-10-04T12:00:00Z",
            windows = listOf(UsageWindow("weekly", "weekly", "Weekly", 60.0)),
            resetCredits = ResetCredits(2, nextCreditId = "credit-2")
        )
        val config =
            ServerConfig(
                ExecutionEnvironmentDescriptor(
                    "a",
                    "a",
                    EnvironmentPlatform("linux", "x64"),
                    "1",
                    ExecutionEnvironmentCapabilities()
                ),
                ServerAuthDescriptor("required", emptyList(), emptyList(), "session"),
                listOf(
                    ProviderInstance(
                        "native",
                        "My provider",
                        "custom",
                        auth = ProviderAuth("Person@Example.test", "Plan"),
                        usageLimits = old
                    )
                ),
                usageLimitSources = listOf(
                    UsageLimitSource(
                        "hub",
                        "cliproxy",
                        "Hub",
                        fresh.checkedAt,
                        listOf(UsageLimitAccount("remote", "custom", "person@example.test", "Plan", fresh))
                    )
                )
            )
        val account = collectLimitAccounts(
            listOf(environment("a").copy(config = config, connected = true, canOperate = true))
        ).single()
        assertEquals(60.0, account.limits.windows.single().usedPercent)
        assertEquals(2, account.limits.resetCredits?.availableCount)
        assertEquals("hub", account.target?.sourceId)
        assertEquals("credit-2", account.target?.creditId)
        assertTrue(account.canRedeem)
        val changed = account.copy(target = account.target!!.copy(creditId = "credit-3"))
        assertNotEquals(accountActionId(account), accountActionId(changed))
    }
}
