package de.chennemann.agentic.domain.usage

import de.chennemann.agentic.t3.contract.ConsumeResetResult
import de.chennemann.agentic.t3.contract.ProviderUsageLimits
import de.chennemann.agentic.t3.contract.UsageBucket
import de.chennemann.agentic.t3.contract.UsageFingerprint
import de.chennemann.agentic.t3.contract.UsageSource
import de.chennemann.agentic.t3.contract.UsageSummaryInput
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

fun usageWindow(days: Int, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): UsageSummaryInput {
    require(days in listOf(1, 7, 30, 90))
    val aligned = now.truncatedTo(ChronoUnit.MINUTES)
    val until = aligned.atZone(zone).toLocalDate()
    val since = if (days ==
        1
    ) {
        aligned.minus(24, ChronoUnit.HOURS).atZone(zone).toLocalDate()
    } else {
        until.minusDays(days - 1L)
    }
    return UsageSummaryInput(
        since.toString(),
        until.toString(),
        zone.id,
        if (days ==
            1
        ) {
            "hour"
        } else {
            "day"
        },
        if (days ==
            1
        ) {
            aligned.minus(24, ChronoUnit.HOURS).toString()
        } else {
            null
        },
        if (days == 1) aligned.toString() else null
    )
}

data class MergedUsage(
    val buckets: List<UsageBucket>,
    val sessions: Map<String, Long>,
    val duplicates: List<String>,
    val mismatches: List<String>
)

/** Physical sources are counted once, with complete scans ahead of newer partial scans. */
fun mergeUsage(environments: List<EnvironmentUsage>, input: UsageSummaryInput): MergedUsage {
    val mismatches = environments.filter {
        it.summary != null && it.summary.contractVersion != 6
    }.map { "${it.environment.label}: usage contract ${it.summary?.contractVersion} is incompatible (supported: 4–6)." }
    val valid = environments.filter {
        it.summary?.let { s ->
            s.contractVersion == 6 &&
                s.sinceDay == input.sinceDay &&
                s.untilDay == input.untilDay &&
                s.timeZone == input.timeZone
        } ==
            true
    }
        .sortedWith(compareByDescending<EnvironmentUsage> { it.summary!!.readAt }.thenBy { it.environment.id })
    data class Claim(val environment: EnvironmentUsage, val source: UsageSource)
    val claims = linkedMapOf<UsageFingerprint, Claim>()
    val duplicates = mutableListOf<String>()
    for (status in listOf("ok", "partial", "failed")) {
        for (environment in valid) {
            for (source in environment.summary!!.sources) {
                if (source.status != status) continue
                if (source.fingerprint in
                    claims
                ) {
                    duplicates += "${environment.environment.label}: ${source.fingerprint.resolvedHomePath}"
                } else {
                    claims[source.fingerprint] =
                        Claim(environment, source)
                }
            }
        }
    }
    fun sourceBuckets(environment: EnvironmentUsage, source: UsageSource) = environment.summary!!.buckets.filter {
        it.provider == source.fingerprint.provider &&
            (
                it.sourcePath == source.fingerprint.resolvedHomePath ||
                    (
                        it.sourcePath == null &&
                            environment.summary.sources.count { s -> s.fingerprint.provider == it.provider } == 1
                        )
                )
    }
    fun cell(bucket: UsageBucket) = listOf(bucket.day, bucket.hourStart, bucket.provider, bucket.model)
    val buckets = mutableListOf<UsageBucket>()
    val sessions = mutableMapOf<String, Long>()
    for ((fingerprint, claim) in claims) {
        val owned = sourceBuckets(claim.environment, claim.source)
        buckets += owned
        val seen = owned.map(::cell).toMutableSet()
        var count = claim.source.distinctSessions
        if (claim.source.status == "ok") {
            for (environment in valid) {
                for (source in environment.summary!!.sources) {
                    if (source.fingerprint != fingerprint ||
                        source.status != "partial" ||
                        environment.summary.readAt <= claim.environment.summary!!.readAt
                    ) {
                        continue
                    }
                    val supplemental = sourceBuckets(environment, source).filter { seen.add(cell(it)) }
                    if (supplemental.isNotEmpty()) {
                        buckets += supplemental
                        count = maxOf(count, source.distinctSessions)
                    }
                }
            }
        }
        sessions[fingerprint.provider] = (sessions[fingerprint.provider] ?: 0) + count
    }
    return MergedUsage(buckets, sessions, duplicates.distinct(), mismatches)
}

data class LimitAccount(
    val key: String,
    val label: String,
    val driver: String,
    val email: String?,
    val plan: String?,
    val locations: List<String>,
    val limits: ProviderUsageLimits,
    val target: ResetTarget?,
    val canRedeem: Boolean
)

fun collectLimitAccounts(environments: List<EnvironmentUsage>): List<LimitAccount> {
    val candidates = mutableListOf<LimitAccount>()
    for (environment in environments.filter { it.connected }) {
        val config = environment.config ?: continue
        for (provider in config.providers.filter { it.enabled && it.installed }) {
            val limits = provider.usageLimits ?: continue
            val driver = provider.driver.orEmpty()
            val key =
                provider.auth.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "$driver:$it" }
                    ?: "${environment.environment.id}:${provider.instanceId}"
            candidates +=
                LimitAccount(
                    key, provider.displayName ?: provider.instanceId, driver, provider.auth.email, provider.auth.label,
                    listOf(
                        environment.environment.label
                    ),
                    limits,
                    ResetTarget(
                        environment.environment.id,
                        instanceId = provider.instanceId
                    ),
                    environment.canManageProviders
                )
        }
        for (source in config.usageLimitSources) {
            for (account in source.accounts) {
                val key =
                    account.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "${account.driver}:$it" }
                        ?: "${environment.environment.id}:${source.id}:${account.id}"
                val credit = account.usageLimits.resetCredits?.nextCreditId
                candidates +=
                    LimitAccount(
                        key, source.label, account.driver, account.email, account.plan,
                        listOf(
                            "${environment.environment.label} · ${source.label}"
                        ),
                        account.usageLimits,
                        credit?.let {
                            ResetTarget(
                                environment.environment.id,
                                sourceId = source.id,
                                accountId = account.id,
                                creditId = it
                            )
                        },
                        environment.canManageProviders
                    )
            }
        }
    }
    return candidates.groupBy { it.key }.map { (_, copies) ->
        val latest = copies.maxWith(
            compareBy<LimitAccount> {
                it.limits.unavailable == null &&
                    it.limits.windows.isNotEmpty()
            }.thenBy { it.limits.checkedAt }
        )
        val credit = copies.filter { it.limits.resetCredits != null }.maxByOrNull { it.limits.checkedAt }
        val hub = copies.filter { it.target?.sourceId != null }.maxByOrNull { it.limits.checkedAt }
        val redeem = hub ?: credit ?: latest
        latest.copy(
            label = copies.first().label,
            locations = copies.flatMap {
                it.locations
            }.distinct(),
            limits = latest.limits.copy(resetCredits = credit?.limits?.resetCredits),
            target = redeem.target,
            canRedeem = redeem.canRedeem
        )
    }
}

/**
 * Usage series are keyed by provider kind. A kind takes the display name its connected provider instances agree on,
 * so newly supported providers read naturally without a client-side table.
 */
fun usageProviderLabels(environments: List<EnvironmentUsage>): Map<String, String> = environments
    .filter { it.connected }
    .flatMap { it.config?.providers.orEmpty() }
    .filter { !it.driver.isNullOrBlank() && !it.displayName.isNullOrBlank() }
    .groupBy({ it.driver!! }, { it.displayName!!.trim() })
    .mapNotNull { (driver, names) -> names.distinct().singleOrNull()?.let { driver to it } }
    .toMap()

fun usageProviderLabel(provider: String, labels: Map<String, String>): String =
    labels[provider] ?: provider.split('-', '_', ' ').filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        .ifEmpty { provider }

/** Disconnected environments keep their cached config; like limits, they must not surface account hints. */
fun hasSharedSubscriptionUsage(environments: List<EnvironmentUsage>): Boolean = environments.any { environment ->
    environment.connected &&
        environment.config?.providers?.any {
            it.enabled && it.auth.status == "authenticated" && it.auth.subscriptionSharing
        } == true
}

fun resetOutcome(result: ConsumeResetResult): String = when (result.outcome) {
    "reset" -> "Reset applied. Your windows have cleared."
    "nothingToReset" -> "Nothing to reset right now."
    "noCredit" -> "No reset credit left."
    "alreadyRedeemed" -> "That credit was already redeemed."
    else -> "Reset result: ${result.outcome}"
} + (result.warning?.let { " $it" } ?: "")

fun accountActionId(account: LimitAccount): String = listOf(
    account.key,
    account.target?.environmentId,
    account.target?.instanceId,
    account.target?.sourceId,
    account.target?.creditId
).joinToString("|")
