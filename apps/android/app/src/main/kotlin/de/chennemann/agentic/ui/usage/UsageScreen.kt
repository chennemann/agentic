package de.chennemann.agentic.ui.usage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun UsageScreen(
    state: UsageUiState,
    onBack: () -> Unit,
    onMetric: (UsageMetric) -> Unit,
    onDays: (Int) -> Unit,
    onEnvironment: (String) -> Unit,
    onAll: () -> Unit,
    onRefresh: (Boolean) -> Unit,
    onRedeem: (String) -> Unit,
    onSavePrice: (Set<String>, String, List<String>, Boolean) -> Unit,
    onEnableKeychain: (String) -> Unit
) {
    BackHandler(onBack = onBack)
    val uriHandler = LocalUriHandler.current
    var confirming by remember { mutableStateOf<LimitAccountUi?>(null) }
    var showPrices by rememberSaveable { mutableStateOf(false) }
    var byModel by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Usage", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { onRefresh(false) }, enabled = !state.loading && !state.busy) { Text("Refresh") }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(UsageMetric.entries) { metric ->
                        FilterChip(state.metric == metric, {
                            onMetric(metric)
                        }, { Text(metric.name.lowercase().replaceFirstChar(Char::uppercase)) })
                    }
                }
                if (state.metric != UsageMetric.LIMITS) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            listOf(1 to "24 hours", 7 to "7 days", 30 to "30 days", 90 to "90 days")
                        ) { (days, label) ->
                            FilterChip(
                                state.days == days,
                                { onDays(days) },
                                { Text(label) }
                            )
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(state.environments.all { it.selected }, onAll, { Text("All environments") }) }
                    items(state.environments, key = {
                        it.id
                    }) { environment ->
                        FilterChip(environment.selected, { onEnvironment(environment.id) }, { Text(environment.label) })
                    }
                }
            }
            if (state.sharedUsage) {
                item {
                    TextButton(onClick = {
                        uriHandler.openUri("https://chatgpt.com/#settings/Usage")
                    }) { Text("Manage ChatGPT shared usage") }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium) } }
            if (state.environments.none { it.selected }) item { Text("Select an environment to see usage.") }
            if (state.metric == UsageMetric.LIMITS) {
                if (state.accounts.isEmpty()) {
                    item {
                        Text("No subscription limits reported. Check environment connections below.")
                    }
                }
                items(
                    state.pools.filter {
                        (it.plan?.substringBefore(' ')?.toIntOrNull() ?: 0) > 1
                    },
                    key = { it.id }
                ) { pool ->
                    UsageCard {
                        Text("${pool.label} pool", style = MaterialTheme.typography.titleMedium)
                        Text(pool.plan.orEmpty())
                        pool.windows.forEach { QuotaWindow(it) }
                    }
                }
                items(state.accounts, key = { it.id }) { account ->
                    AccountCard(account, state.busy) {
                        confirming =
                            account
                    }
                }
            } else {
                item {
                    UsageCard {
                        Text(state.total, style = MaterialTheme.typography.headlineLarge)
                        Text(state.sessions)
                        if (state.metric ==
                            UsageMetric.COST
                        ) {
                            Text(
                                "API-equivalent estimate; subscription charges are separate. Unpriced usage is excluded from cost.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        state.providers.forEach { row ->
                            DataRow(row)
                            LinearProgressIndicator(progress = { row.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item {
                    UsageCard {
                        Text(
                            if (state.days ==
                                1
                            ) {
                                "Hourly usage"
                            } else {
                                "Daily usage"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        UsageChart(state.periods.asReversed(), state.metric)
                    }
                }
                item {
                    UsageCard {
                        Text("Totals", style = MaterialTheme.typography.titleMedium)
                        state.totals.forEach { DataRow(it) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(byModel, { byModel = true }, { Text("By model") })
                        FilterChip(!byModel, { byModel = false }, {
                            Text(
                                if (state.days ==
                                    1
                                ) {
                                    "By hour"
                                } else {
                                    "By day"
                                }
                            )
                        })
                    }
                }
                val rows = if (byModel) state.models else state.periods
                if (rows.isEmpty()) item { Text("No activity in this window.") }
                items(rows, key = { it.id }) { row -> UsageCard { DataRow(row) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showPrices = true }) { Text("Custom prices") }
                        TextButton(onClick = {
                            onRefresh(true)
                        }, enabled = !state.loading && !state.busy) { Text("Refresh rates") }
                    }
                }
                item {
                    UsageCard {
                        Text("Pricing and coverage", style = MaterialTheme.typography.titleMedium)
                        state.pricing.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            items(
                state.environments.filter {
                    it.selected && it.enableKeychain
                },
                key = { "keychain:${it.id}" }
            ) { environment ->
                UsageCard {
                    Text("Cursor account usage Ã‚· ${environment.label}")
                    Text(
                        "Requires access to your Cursor login in macOS Keychain.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { onEnableKeychain(environment.id) }, enabled = !state.busy) { Text("Enable") }
                }
            }
            item {
                UsageCard {
                    Text("Environment status", style = MaterialTheme.typography.titleMedium)
                    state.environments.filter {
                        it.selected
                    }.forEach { Text("${it.label}: ${it.status}", style = MaterialTheme.typography.bodySmall) }
                    state.coverage.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    confirming?.let { account ->
        val current = state.accounts.firstOrNull { it.id == account.id }
        AlertDialog(onDismissRequest = {
            if (!state.busy) confirming = null
        }, title = {
            Text("Redeem a token reset?")
        }, text = {
            Text(
                "Use one reset credit for ${account.label} to clear its current usage windows? ${account.credits.orEmpty()}"
            )
        }, confirmButton = {
            TextButton(enabled = current?.redeemEnabled == true && !state.busy, onClick = {
                onRedeem(account.id)
                confirming =
                    null
            }) { Text("Redeem reset") }
        }, dismissButton = { TextButton(onClick = { confirming = null }, enabled = !state.busy) { Text("Cancel") } })
    }
    if (showPrices) PriceDialog(state, { showPrices = false }, onSavePrice)
}

@Composable
private fun UsageCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun DataRow(row: UsageRowUi) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(row.value, style = MaterialTheme.typography.titleSmall)
        }
        if (row.detail.isNotBlank()) {
            Text(
                row.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuotaWindow(window: LimitWindowUi) {
    Text("${window.label} Ã‚· ${(window.remaining * 100).toInt()}% left", style = MaterialTheme.typography.titleSmall)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        drawRect(track, size = Size(size.width, size.height))
        drawRect(primary, size = Size(size.width * window.remaining, size.height))
        window.timeLeft?.let {
            drawLine(marker, Offset(size.width * it, 0f), Offset(size.width * it, size.height), 2.dp.toPx())
        }
    }
    Text(window.detail, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AccountCard(account: LimitAccountUi, busy: Boolean, onRedeem: () -> Unit) {
    var reveal by rememberSaveable(account.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    UsageCard {
        Text(account.label, style = MaterialTheme.typography.titleMedium)
        account.plan?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(account.locations, style = MaterialTheme.typography.bodySmall)
        account.email?.let { email ->
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) email else "Reveal account") }
        }
        account.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        account.windows.forEach { QuotaWindow(it) }
        account.credits?.let {
            Text(it)
            TextButton(onClick = onRedeem, enabled = account.redeemEnabled && !busy) { Text("Redeem token reset") }
        }
        account.externalUrl?.let { url ->
            TextButton(onClick = {
                runCatching {
                    val scheme = java.net.URI(url).scheme
                    if (scheme == "https" ||
                        scheme == "http"
                    ) {
                        uri.openUri(url)
                    }
                }
            }) { Text(account.externalLabel ?: "Manage usage") }
        }
    }
}

@Composable
private fun UsageChart(rows: List<UsageRowUi>, metric: UsageMetric) {
    var selected by remember(rows) { mutableStateOf<UsageRowUi?>(null) }
    val colors =
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            Color(0xFF4CAF50),
            Color(0xFFEE8D39),
            Color(0xFF8B7CD8)
        )
    val providers = rows.flatMap { it.series }.map { it.provider }.distinct().sorted()
    val maximum = rows.maxOfOrNull { it.fraction }?.takeIf { it > 0 } ?: 1f
    Canvas(
        Modifier.fillMaxWidth().height(140.dp).semantics {
            contentDescription =
                "Usage by time. Exact values in time breakdown."
        }.pointerInput(rows) {
            detectTapGestures { offset ->
                if (rows.isNotEmpty()) {
                    selected =
                        rows[(offset.x / size.width * rows.size).toInt().coerceIn(0, rows.lastIndex)]
                }
            }
        }
    ) {
        if (rows.isNotEmpty()) {
            val width = size.width / rows.size
            val barWidth = (width - 2.dp.toPx()).coerceAtLeast(1f)
            rows.forEachIndexed { index, row ->
                val barHeight = row.series.sumOf { (size.height * it.fraction / maximum).toDouble() }.toFloat()
                if (barHeight <= 0f) return@forEachIndexed
                // Stacked segments share one outline, rounded only where the bar ends at the top.
                val corner = CornerRadius(minOf(4.dp.toPx(), barWidth / 2, barHeight))
                val outline = Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(Offset(width * index, size.height - barHeight), Size(barWidth, barHeight)),
                            topLeft = corner,
                            topRight = corner
                        )
                    )
                }
                clipPath(outline) {
                    var bottom = size.height
                    row.series.forEach { series ->
                        val height = size.height * series.fraction / maximum
                        drawRect(
                            colors[providers.indexOf(series.provider) % colors.size],
                            Offset(
                                width * index,
                                bottom - height
                            ),
                            Size(barWidth, height)
                        )
                        bottom -= height
                    }
                }
            }
        }
    }
    Text(
        "${rows.firstOrNull()?.label.orEmpty()} \u00b7 ${rows.lastOrNull()?.label.orEmpty()} \u00b7 ${if (metric == UsageMetric.COST) "cost" else "tokens"}",
        style = MaterialTheme.typography.bodySmall
    )
    Text(providers.joinToString(" \u00b7 "), style = MaterialTheme.typography.bodySmall)
    selected?.let { row ->
        DataRow(row)
        row.series.forEach { Text("${it.provider}: ${it.value}", style = MaterialTheme.typography.bodySmall) }
    }
    Text("Tap a bar for details, or use the time breakdown.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PriceDialog(
    state: UsageUiState,
    onDismiss: () -> Unit,
    onSave: (Set<String>, String, List<String>, Boolean) -> Unit
) {
    var destinations by remember {
        mutableStateOf(state.environments.filter { it.selected && it.priceEnabled }.map { it.id }.toSet())
    }
    var model by rememberSaveable { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var output by rememberSaveable { mutableStateOf("") }
    var read by rememberSaveable { mutableStateOf("") }
    var write by rememberSaveable { mutableStateOf("") }
    val valid = destinations.isNotEmpty() && model.isNotBlank() && validPriceFields(listOf(input, output, read, write))
    AlertDialog(onDismissRequest = { if (!state.busy) onDismiss() }, title = { Text("Custom model prices") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("USD per million tokens. Blank cache prices use the input price; zero means free.") }
            items(state.environments, key = { it.id }) { environment ->
                FilterChip(
                    environment.id in destinations,
                    {
                        destinations =
                            if (environment.id in
                                destinations
                            ) {
                                destinations - environment.id
                            } else {
                                destinations + environment.id
                            }
                    },
                    {
                        Text(
                            environment.label + if (!environment.priceEnabled) " Ã‚· unavailable" else ""
                        )
                    },
                    enabled =
                    environment.priceEnabled && !state.busy
                )
            }
            val prices = state.environments.filter { it.id in destinations }.flatMap { it.prices }.distinct()
            items(prices, key = { "${it.model}:${it.input}:${it.output}:${it.cacheRead}:${it.cacheWrite}" }) { price ->
                TextButton(enabled = !state.busy, onClick = {
                    model = price.model
                    input = price.input
                    output =
                        price.output
                    read = price.cacheRead
                    write = price.cacheWrite
                }) { Text("${price.model} Ã‚· ${price.input}/${price.output}") }
            }
            item { OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, enabled = !state.busy) }
            item { OutlinedTextField(input, { input = it }, label = { Text("Input") }, enabled = !state.busy) }
            item { OutlinedTextField(output, { output = it }, label = { Text("Output") }, enabled = !state.busy) }
            item {
                OutlinedTextField(read, { read = it }, label = { Text("Cache read (optional)") }, enabled = !state.busy)
            }
            item {
                OutlinedTextField(write, {
                    write = it
                }, label = { Text("Cache write (optional)") }, enabled = !state.busy)
            }
            item {
                TextButton(enabled = destinations.isNotEmpty() && model.isNotBlank() && !state.busy, onClick = {
                    onSave(destinations, model, emptyList(), true)
                }) { Text("Restore default prices") }
            }
            state.message?.let { item { Text(it) } }
        }
    }, confirmButton = {
        TextButton(enabled = valid && !state.busy, onClick = {
            onSave(destinations, model, listOf(input, output, read, write), false)
        }) { Text(if (state.busy) "SavingÃ¢â‚¬Â¦" else "Save") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Close") } })
}
