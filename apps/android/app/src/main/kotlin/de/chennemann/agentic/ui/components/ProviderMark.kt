package de.chennemann.agentic.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.ProviderClaude
import de.chennemann.agentic.icons.ProviderCodex
import de.chennemann.agentic.icons.ProviderCursor
import de.chennemann.agentic.icons.ProviderDevin
import de.chennemann.agentic.icons.ProviderGrok
import de.chennemann.agentic.icons.ProviderOpenCode

/**
 * The mark of the provider serving a model. T3 reports each instance's driver kind but ships no
 * artwork, so marks are bundled like in T3's own clients; unknown drivers get the label's initial.
 */
@Composable
fun ProviderMark(
    driver: String?,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = LocalContentColor.current,
) {
    val sized = modifier.size(size)
    when (val mark = providerMark(driver)) {
        null -> Box(
            modifier = sized.border(1.dp, tint.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.trim().take(1).uppercase(),
                color = tint,
                fontSize = (size.value * 0.55f).sp,
                lineHeight = (size.value * 0.55f).sp,
            )
        }
        Icons.ProviderClaude -> Image(mark, contentDescription = null, modifier = sized)
        else -> Icon(mark, contentDescription = null, modifier = sized, tint = tint)
    }
}

private fun providerMark(driver: String?): ImageVector? = when (driver?.trim()) {
    "codex" -> Icons.ProviderCodex
    "claudeAgent" -> Icons.ProviderClaude
    "cursor" -> Icons.ProviderCursor
    "grok" -> Icons.ProviderGrok
    "opencode" -> Icons.ProviderOpenCode
    "devin" -> Icons.ProviderDevin
    else -> null
}
