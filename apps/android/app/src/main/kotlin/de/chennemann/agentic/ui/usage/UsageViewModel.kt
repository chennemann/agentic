package de.chennemann.agentic.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.usage.UsageService
import de.chennemann.agentic.domain.usage.accountActionId
import de.chennemann.agentic.domain.usage.collectLimitAccounts
import de.chennemann.agentic.domain.usage.mergeUsage
import de.chennemann.agentic.domain.usage.resetOutcome
import de.chennemann.agentic.domain.usage.usageWindow
import de.chennemann.agentic.t3.contract.UsageBucket
import de.chennemann.agentic.t3.contract.UsageModelPrice
import de.chennemann.agentic.t3.contract.UsageSummaryInput
import de.chennemann.agentic.t3.contract.UsageWindow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UsageMetric { COST, TOKENS, LIMITS }
data class UsageSeriesUi(val provider: String, val fraction: Float, val value: String)
data class UsageRowUi(
    val id: String,
    val label: String,
    val value: String,
    val detail: String = "",
    val fraction: Float = 0f,
    val series: List<UsageSeriesUi> = emptyList()
)
data class LimitWindowUi(
    val id: String,
    val label: String,
    val remaining: Float,
    val detail: String,
    val timeLeft: Float? = null
)
data class LimitAccountUi(
    val id: String,
    val label: String,
    val plan: String?,
    val email: String?,
    val locations: String,
    val windows: List<LimitWindowUi>,
    val notice: String?,
    val credits: String?,
    val redeemEnabled: Boolean,
    val externalLabel: String? = null,
    val externalUrl: String? = null
)
data class PriceUi(
    val model: String,
    val input: String,
    val output: String,
    val cacheRead: String,
    val cacheWrite: String
)
data class UsageEnvironmentUi(
    val id: String,
    val label: String,
    val selected: Boolean,
    val status: String,
    val priceEnabled: Boolean,
    val prices: List<PriceUi>,
    val enableKeychain: Boolean
)
data class UsageUiState(
    val metric: UsageMetric = UsageMetric.COST,
    val days: Int = 7,
    val environments: List<UsageEnvironmentUi> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val total: String = "—",
    val sessions: String = "",
    val totals: List<UsageRowUi> = emptyList(),
    val providers: List<UsageRowUi> = emptyList(),
    val models: List<UsageRowUi> = emptyList(),
    val periods: List<UsageRowUi> = emptyList(),
    val coverage: List<String> = emptyList(),
    val accounts: List<LimitAccountUi> = emptyList(),
    val pools: List<LimitAccountUi> = emptyList(),
    val pricing: List<String> = emptyList(),
    val sharedUsage: Boolean = false
)
private data class Selection(
    val metric: UsageMetric = UsageMetric.COST,
    val days: Int = 7,
    val ids: Set<String>? = null,
    val input: UsageSummaryInput = usageWindow(7)
)

class UsageViewModel(
    private val service: UsageService,
    private val preferences: de.chennemann.agentic.data.cache.SqlUsagePreferences
) : ViewModel() {
    private val selection = MutableStateFlow(Selection())
    private val loading = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val now = MutableStateFlow(Instant.now())
    private var refreshJob: Job? = null
    private val accounts get() = collectLimitAccounts(selectedEnvironments())
    val state = combine(service.repository.state, selection, loading, busy, message) { _, _, _, _, _ -> mapState() }
        .combine(now) { _, _ -> mapState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsageUiState(loading = true))

    init {
        viewModelScope.launch {
            val (metric, days) = preferences.read()
            selection.value =
                Selection(
                    UsageMetric.entries.firstOrNull {
                        it.name == metric
                    } ?: UsageMetric.COST,
                    days,
                    input = usageWindow(days)
                )
            service.observe()
        }
        viewModelScope.launch {
            service.repository.state.map { it.keys }.distinctUntilChanged().collect { refresh() }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                now.value = Instant.now()
            }
        }
    }

    fun selectMetric(metric: UsageMetric) {
        selection.update { it.copy(metric = metric) }
        savePreferences()
        if (metric ==
            UsageMetric.LIMITS
        ) {
            refresh()
        }
    }
    fun selectDays(days: Int) {
        selection.update { it.copy(days = days, input = usageWindow(days)) }
        savePreferences()
        refresh()
    }
    private fun savePreferences() {
        val current = selection.value
        viewModelScope.launch { preferences.save(current.metric.name, current.days) }
    }
    fun selectEnvironment(id: String) {
        selection.update { current ->
            val ids = current.ids ?: service.repository.state.value.keys
            current.copy(ids = if (id in ids) ids - id else ids + id)
        }
        refresh()
    }
    fun selectAll() {
        selection.update { it.copy(ids = null) }
        refresh()
    }
    fun refresh(rates: Boolean = false) {
        refreshJob?.cancel()
        selection.update { it.copy(input = usageWindow(it.days)) }
        val selected = selection.value
        refreshJob = viewModelScope.launch {
            loading.value = true
            try {
                service.refresh(selected.ids, selected.input, rates)
                if (selected.metric == UsageMetric.LIMITS) service.refreshLimits(selected.ids)
            } finally {
                loading.value = false
            }
        }
    }
    fun redeem(id: String) {
        val account = accounts.firstOrNull { accountActionId(it) == id } ?: return
        val target = account.target ?: return
        if (!account.canRedeem || (account.limits.resetCredits?.availableCount ?: 0) <= 0) return
        action { resetOutcome(service.redeem(target)) }
    }
    fun savePrice(ids: Set<String>, model: String, fields: List<String>, remove: Boolean = false) {
        val price = if (remove) null else parsePrice(fields)
        if (ids.isEmpty() ||
            model.isBlank() ||
            (!remove && price == null)
        ) {
            message.value =
                "Select destinations, enter a model ID and finite nonnegative input/output prices. Cache prices may be blank."
            return
        }
        action {
            val results = service.savePrice(ids, model, price)
            refresh()
            results.entries.joinToString("\n") { (id, success) ->
                "${service.repository.state.value[id]?.environment?.label ?: id}: ${if (success) "Saved" else "Failed — retry this destination"}"
            }
        }
    }
    fun enableKeychain(id: String) = action {
        service.enableKeychain(id)
        refresh()
        "Cursor account usage enabled."
    }
    private fun action(block: suspend () -> String) {
        if (busy.value) return
        busy.value = true
        message.value = null
        viewModelScope.launch {
            try {
                message.value = block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                _: Exception
            ) {
                message.value = "The operation failed. Refresh and try again."
            } finally {
                busy.value = false
            }
        }
    }
    private fun selectedEnvironments() = service.repository.state.value.values.filter {
        selection.value.ids == null ||
            it.environment.id in selection.value.ids!!
    }
    private fun mapState(): UsageUiState {
        val selected = selection.value
        val environments = selectedEnvironments()
        val merged = mergeUsage(environments, selected.input)
        val buckets = merged.buckets
        val cost = buckets.sumOf { it.costUsd }
        val tokens = buckets.sumOf { it.totals.total }
        fun rows(groups: Map<String, List<UsageBucket>>, labels: (String) -> String = { it }): List<UsageRowUi> =
            groups.map { (key, values) ->
                val c = values.sumOf { it.costUsd }
                val t = values.sumOf { it.totals.total }
                val records = values.sumOf { it.records }
                val unpriced = values.sumOf { it.unpricedRecords }
                val unknown = records > 0 && unpriced >= records
                val fraction = if (selected.metric ==
                    UsageMetric.COST
                ) if (cost > 0) c / cost else 0.0 else if (tokens > 0) {
                    t.toDouble() / tokens
                } else {
                    0.0
                }
                UsageRowUi(
                    key,
                    labels(key),
                    if (selected.metric ==
                        UsageMetric.COST
                    ) if (unknown) "Unknown cost" else usd(c) else {
                        count(t)
                    },
                    "${usd(c)} · ${count(t)} tokens · ${percent(fraction * 100)} share" +
                        if (unpriced > 0) " · $unpriced unpriced responses" else "",
                    fraction.toFloat()
                )
            }
        val limitAccounts = accounts
        val pooled = limitAccounts.filter {
            it.limits.unavailable == null
        }.groupBy { it.driver }.map { (driver, members) ->
            val windows = members.flatMap { a -> a.limits.windows.map { a to it } }.groupBy {
                it.second.kind + ":" +
                    it.second.id
            }.map { (id, entries) ->
                val remaining = (100 - entries.map { it.second.usedPercent }.average()).toFloat() / 100
                val timed = entries.mapNotNull { (_, window) ->
                    timeLeft(window, now.value)?.let {
                        window.usedPercent to
                            it
                    }
                }
                val meanTimeLeft = timed.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.toFloat()
                val pace = meanTimeLeft?.let { left ->
                    val difference = timed.map { it.first }.average() - (1 - left) * 100
                    if (difference > 5) {
                        "Ahead of pace"
                    } else if (difference < -5) {
                        "Under pace"
                    } else {
                        "On pace"
                    }
                }
                LimitWindowUi(
                    id,
                    entries.first().second.label,
                    remaining,
                    listOfNotNull(pace).joinToString() + "\n" +
                        entries.sortedBy { it.second.resetsAt }.joinToString("\n") { (a, w) ->
                            "${a.label}: ${windowDetail(w, now.value)} · restores ${percent(
                                w.usedPercent / entries.size
                            )} of pool"
                        },
                    meanTimeLeft
                )
            }
            LimitAccountUi("pool:$driver", driver, "${members.size} accounts", null, "", windows, null, null, false)
        }
        val envRows = service.repository.state.value.values.map { env ->
            val prices = (env.settings ?: env.config?.settings)?.usagePriceOverrides.orEmpty().map { (model, price) ->
                PriceUi(
                    model,
                    price.inputCostPerMillionTokens.toString(),
                    price.outputCostPerMillionTokens.toString(),
                    price.cacheReadCostPerMillionTokens?.toString().orEmpty(),
                    price.cacheWriteCostPerMillionTokens?.toString().orEmpty()
                )
            }
            UsageEnvironmentUi(
                env.environment.id,
                env.environment.label,
                selected.ids == null || env.environment.id in selected.ids,
                env.error ?: if (env.connected) "Connected" else "Connecting",
                env.connected &&
                    env.canOperate &&
                    env.config?.environment?.capabilities?.usagePriceOverrides == true,
                prices,
                env.canOperate &&
                    env.summary?.sources?.any { it.action == "enableCursorKeychain" } == true
            )
        }
        val records = buckets.sumOf { it.records }
        val coverage =
            environments.flatMap { env ->
                listOfNotNull(env.error?.let { "${env.environment.label}: $it" }) +
                    env.summary?.sources.orEmpty().mapNotNull { source ->
                        source.message?.let { "${env.environment.label}: $it" }
                    } +
                    env.config?.usageLimitSources.orEmpty().mapNotNull { source ->
                        source.error?.let { "${source.label}: $it" }
                    }
            } +
                merged.mismatches +
                merged.duplicates.map { "Duplicate source excluded: $it" }
        val quality = listOf("providerReported", "modelPriced", "unpriced").map { source ->
            "$source: ${percent(
                if (records > 0) {
                    buckets.filter {
                        it.costSource == source
                    }.sumOf { it.records }.toDouble() / records * 100
                } else {
                    0.0
                }
            )} of responses"
        }
        val periodGroups = buckets.groupBy {
            it.hourStart?.let { hour -> runCatching { Instant.parse(hour).toString() }.getOrDefault(hour) }
                ?: it.day
        }
        val periodKeys = if (selected.days == 1) {
            val start = Instant.parse(selected.input.sinceTime)
            (0..23).map { start.plusSeconds(it * 3600L).toString() }
        } else {
            val start = java.time.LocalDate.parse(selected.input.sinceDay)
            (0 until selected.days).map { start.plusDays(it.toLong()).toString() }
        }
        val periods = periodKeys.map { key ->
            val values = periodGroups[key].orEmpty()
            val row = rows(mapOf(key to values), ::periodLabel).first()
            row.copy(
                series = values.groupBy { it.provider }.map { (provider, cells) ->
                    val amount = if (selected.metric ==
                        UsageMetric.COST
                    ) {
                        cells.sumOf { it.costUsd }
                    } else {
                        cells.sumOf { it.totals.total }.toDouble()
                    }
                    val total = if (selected.metric == UsageMetric.COST) cost else tokens.toDouble()
                    UsageSeriesUi(
                        provider,
                        if (total >
                            0
                        ) {
                            (amount / total).toFloat()
                        } else {
                            0f
                        },
                        if (selected.metric ==
                            UsageMetric.COST
                        ) {
                            usd(amount)
                        } else {
                            count(amount.toLong())
                        }
                    )
                }
            )
        }.asReversed()
        return UsageUiState(
            selected.metric, selected.days, envRows, loading.value, busy.value, message.value,
            if (environments.none { it.summary != null }) {
                "?"
            } else if (selected.metric ==
                UsageMetric.COST
            ) {
                usd(cost)
            } else {
                count(tokens)
            },
            "${count(merged.sessions.values.sum())} sessions · ${count(records)} responses",
            listOf(
                UsageRowUi("processed", "Processed tokens", count(tokens)),
                UsageRowUi(
                    "uncached",
                    "Uncached input",
                    count(
                        buckets.sumOf {
                            it.totals.uncachedInputTokens
                        }
                    )
                ),
                UsageRowUi(
                    "cached",
                    "Cached input",
                    count(
                        buckets.sumOf {
                            it.totals.cachedInputTokens
                        }
                    )
                ),
                UsageRowUi(
                    "creation",
                    "Cache creation",
                    count(
                        buckets.sumOf {
                            it.totals.cacheCreationTokens
                        }
                    )
                ),
                UsageRowUi(
                    "output",
                    "Output",
                    count(
                        buckets.sumOf {
                            it.totals.outputTokens
                        }
                    )
                ),
                UsageRowUi(
                    "reasoning",
                    "Reasoning (included in output)",
                    count(
                        buckets.sumOf {
                            it.totals.reasoningTokens
                        }
                    )
                ),
                UsageRowUi("savings", "Cache savings", usd(buckets.sumOf { it.cacheSavingsUsd }))
            ),
            rows(
                buckets.groupBy {
                    it.provider
                }
            ).map { row -> row.copy(detail = "${merged.sessions[row.id] ?: 0} sessions · ${row.detail}") },
            rows(
                buckets.groupBy {
                    it.provider + " / " + it.model
                }
            ).sortedByDescending { it.fraction },
            periods, coverage,
            limitAccounts.map { account ->
                val credit = account.limits.resetCredits
                LimitAccountUi(
                    accountActionId(
                        account
                    ),
                    account.label, account.plan, account.email, account.locations.joinToString(" · "),
                    account.limits.windows.map { window ->
                        val left = timeLeft(window, now.value)
                        LimitWindowUi(
                            window.id,
                            window.label,
                            ((100 - window.usedPercent) / 100).toFloat().coerceIn(0f, 1f),
                            windowDetail(window, now.value),
                            left
                        )
                    },
                    account.limits.unavailable?.message ?: account.limits.unavailable?.reason
                        ?: if (account.limits.windows.isEmpty()) "No limits reported." else null,
                    credit?.let {
                        "${it.availableCount} reset credits" +
                            (it.nextExpiresAt?.let { at -> " · next expires ${timestamp(at)}" } ?: "")
                    },
                    account.canRedeem &&
                        account.target != null &&
                        (credit?.availableCount ?: 0) > 0 &&
                        !busy.value,
                    account.limits.externalUsage?.label, account.limits.externalUsage?.url
                )
            },
            pooled,
            environments.mapNotNull { env ->
                env.summary?.let {
                    val fetched = it.pricing.fetchedAt?.let(::timestamp) ?: "never"
                    "${env.environment.label}: ${it.pricing.status} rates from ${it.pricing.source}" +
                        " \u00b7 ${it.pricing.knownModels} models \u00b7 fetched $fetched" +
                        " \u00b7 read ${timestamp(it.readAt)} \u00b7 scan ${it.scanDurationMs} ms"
                }
            } +
                quality,
            environments.any { env ->
                env.config?.providers?.any {
                    it.auth.status == "authenticated" &&
                        it.auth.subscriptionSharing
                } ==
                    true
            }
        )
    }
}

fun parsePrice(fields: List<String>): UsageModelPrice? {
    if (fields.size != 4) return null
    val values = fields.mapIndexed { index, raw ->
        if (raw.isBlank() && index >= 2) {
            null
        } else {
            if (!raw.trim().matches(Regex("(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"))) return null
            raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        }
    }
    return UsageModelPrice(values[0] ?: return null, values[1] ?: return null, values[2], values[3])
}
fun validPriceFields(fields: List<String>) = parsePrice(fields) != null
private fun usd(value: Double) = String.format(Locale.US, "$%,.2f", value)
private fun count(value: Long) = String.format(Locale.getDefault(), "%,d", value)
private fun percent(value: Double) = String.format(Locale.US, "%.0f%%", value)
private fun timestamp(value: String) = runCatching {
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
private fun periodLabel(value: String) = if ('T' in value) timestamp(value) else value
private fun timeLeft(window: UsageWindow, now: Instant): Float? {
    val duration = window.windowDurationMins?.takeIf { it > 0 } ?: return null
    val reset = window.resetsAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return ((reset.toEpochMilli() - now.toEpochMilli()).toDouble() / (duration * 60_000)).toFloat().coerceIn(0f, 1f)
}
private fun windowDetail(window: UsageWindow, now: Instant): String {
    val reset = window.resetsAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val mins = reset?.let { ((it.toEpochMilli() - now.toEpochMilli()) / 60_000).coerceAtLeast(0) }
    val left = timeLeft(window, now)
    val pace = left?.let {
        val difference =
            window.usedPercent - (1 - it) * 100
        if (difference >
            5
        ) {
            "Ahead of pace"
        } else if (difference < -5) {
            "Under pace"
        } else {
            "On pace"
        }
    }
    return listOfNotNull(
        pace,
        left?.let {
            "${percent(it * 100.0)} of window left"
        },
        mins?.let { "Resets in ${it / 60}h ${it % 60}m (${timestamp(window.resetsAt!!)})" },
        if (reset ==
            null
        ) {
            "Reset time unavailable"
        } else {
            null
        }
    ).joinToString(" · ")
}
