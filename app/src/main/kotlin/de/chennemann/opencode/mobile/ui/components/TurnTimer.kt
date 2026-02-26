package de.chennemann.opencode.mobile.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
fun TurnTimer(
    startedAt: Long,
    completedAt: Long?,
    modifier: Modifier = Modifier,
    text: @Composable (formatted: String) -> Unit = { formatted ->
        Text(text = formatted, modifier = modifier)
    },
) {
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(startedAt, completedAt) {
        if (completedAt != null) return@LaunchedEffect
        while (true) {
            delay(1_000)
            tick++
        }
    }

    val now = remember(tick) { System.currentTimeMillis() }
    val end = completedAt ?: now
    val elapsedSeconds = ((end - startedAt).coerceAtLeast(0L) / 1_000L)

    text(formatElapsed(elapsedSeconds))
}

private fun formatElapsed(elapsedSeconds: Long): String {
    val totalSeconds = elapsedSeconds.coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> {
            "${hours}h ${minutes.toString().padStart(2, '0')}m " +
                "${seconds.toString().padStart(2, '0')}s"
        }

        minutes > 0 -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        else -> "${seconds}s"
    }
}
