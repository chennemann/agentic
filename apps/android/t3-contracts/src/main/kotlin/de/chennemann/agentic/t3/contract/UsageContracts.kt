package de.chennemann.agentic.t3.contract

import kotlinx.serialization.Serializable

@Serializable
data class UsageWindow(
    val id: String,
    val kind: String,
    val label: String,
    val usedPercent: Double,
    val resetsAt: String? = null,
    val windowDurationMins: Long? = null
)

@Serializable
data class ResetCredits(val availableCount: Int, val nextExpiresAt: String? = null, val nextCreditId: String? = null)

@Serializable
data class ExternalUsage(val label: String, val url: String)

@Serializable
data class UsageUnavailable(val reason: String, val message: String? = null)

@Serializable
data class ProviderUsageLimits(
    val checkedAt: String,
    val windows: List<UsageWindow> = emptyList(),
    val resetCredits: ResetCredits? = null,
    val externalUsage: ExternalUsage? = null,
    val unavailable: UsageUnavailable? = null
)

@Serializable
data class ProviderAuth(
    val email: String? = null,
    val label: String? = null,
    val status: String? = null,
    val subscriptionSharing: Boolean = false
)

@Serializable
data class UsageLimitAccount(
    val id: String,
    val driver: String,
    val email: String? = null,
    val plan: String? = null,
    val usageLimits: ProviderUsageLimits
)

@Serializable
data class UsageLimitSource(
    val id: String,
    val kind: String,
    val label: String,
    val checkedAt: String,
    val accounts: List<UsageLimitAccount> = emptyList(),
    val error: String? = null
)

@Serializable
data class ConsumeResetResult(val outcome: String, val warning: String? = null)

@Serializable
data class UsageTokens(
    val uncachedInputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0
) {
    val total: Long get() = uncachedInputTokens + cachedInputTokens + cacheCreationTokens + outputTokens
}

@Serializable
data class UsageBucket(
    val day: String,
    val provider: String,
    val model: String,
    val totals: UsageTokens,
    val costUsd: Double,
    val cacheSavingsUsd: Double,
    val costSource: String,
    val records: Long,
    val unpricedRecords: Long,
    val sessions: Long,
    val hourStart: String? = null,
    val sourcePath: String? = null
)

@Serializable
data class UsageFingerprint(
    val hostId: String,
    val provider: String,
    val resolvedHomePath: String,
    val volumeId: String
)

@Serializable
data class UsageSource(
    val fingerprint: UsageFingerprint,
    val status: String,
    val scannedFiles: Long,
    val skippedFiles: Long,
    val malformedRecords: Long,
    val distinctSessions: Long,
    val message: String? = null,
    val action: String? = null,
    /** The server answered from cache while it rescans; repeat with [UsageSummaryInput.awaitRefresh]. */
    val refreshing: Boolean = false
)

@Serializable
data class UsagePricing(val status: String, val source: String, val fetchedAt: String? = null, val knownModels: Long)

@Serializable
data class UsageSummary(
    val contractVersion: Int,
    val readAt: String,
    val timeZone: String,
    val sinceDay: String,
    val untilDay: String,
    val buckets: List<UsageBucket>,
    val sources: List<UsageSource>,
    val pricing: UsagePricing,
    val scanDurationMs: Long
)

@Serializable
data class UsageSummaryInput(
    val sinceDay: String,
    val untilDay: String,
    val timeZone: String,
    val resolution: String,
    val sinceTime: String? = null,
    val untilTime: String? = null,
    val awaitRefresh: Boolean? = null
)

@Serializable
data class UsageModelPrice(
    val inputCostPerMillionTokens: Double,
    val outputCostPerMillionTokens: Double,
    val cacheReadCostPerMillionTokens: Double? = null,
    val cacheWriteCostPerMillionTokens: Double? = null
)
