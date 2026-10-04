package de.chennemann.agentic.ui.chat

import java.time.Instant

internal fun formatTurnDuration(
    startedAt: String,
    completedAt: String?,
    nowMillis: Long,
): String? {
    val startMillis = runCatching { Instant.parse(startedAt).toEpochMilli() }.getOrNull() ?: return null
    val endMillis = if (completedAt == null) nowMillis else {
        runCatching { Instant.parse(completedAt).toEpochMilli() }.getOrNull() ?: return null
    }
    val seconds = ((endMillis - startMillis).coerceAtLeast(0L) / 1_000L)
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    if (minutes < 60) return "${minutes}m ${remainingSeconds}s"
    val hours = minutes / 60
    return "${hours}h ${minutes % 60}m"
}
