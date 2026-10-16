package de.chennemann.agentic.ui.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import de.chennemann.agentic.ui.theme.LocalCodeScale
import expo.modules.t3terminal.T3TerminalView
import java.util.Locale

/** App-owned styling through upstream view properties; never reapplied for streamed output. */
internal data class TerminalAppearance(
    val background: String,
    val foreground: String,
    val mutedForeground: String,
    val scheme: String,
    val config: String,
    val fontSize: Float
) {
    fun applyTo(view: T3TerminalView) {
        if (view.backgroundColorHex != background) view.backgroundColorHex = background
        if (view.foregroundColorHex != foreground) view.foregroundColorHex = foreground
        if (view.mutedForegroundColorHex != mutedForeground) view.mutedForegroundColorHex = mutedForeground
        if (view.themeConfig != config) view.themeConfig = config
        if (view.fontSize != fontSize) view.fontSize = fontSize
        view.appearanceScheme = scheme
    }
}

@Composable
internal fun rememberTerminalAppearance(): TerminalAppearance {
    val colors = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val nativeScaledDensity = configuration.densityDpi / 160f * configuration.fontScale
    // Canvas uses Android resources; account for the app's Compose interface and code scales.
    val fontSize = with(LocalDensity.current) { 13.sp.toPx() } * LocalCodeScale.current / nativeScaledDensity
    return remember(colors, fontSize) {
        val dark = colors.surface.luminance() < 0.5f
        val chromatic = if (dark) {
            listOf(
                colors.error,
                Color(0xFFA0CC5B),
                Color(0xFFE5C07B),
                Color(0xFF82AAFF),
                Color(0xFFC792EA),
                Color(0xFF7FCFCE)
            )
        } else {
            listOf(
                colors.error,
                Color(0xFF326B1D),
                Color(0xFF805600),
                Color(0xFF2459A6),
                Color(0xFF8144A0),
                Color(0xFF006B68)
            )
        }
        val palette = listOf(colors.surface) + chromatic + colors.onSurfaceVariant +
            colors.outline + chromatic.map { lerp(it, colors.onSurface, 0.2f) } + colors.onSurface
        TerminalAppearance(
            background = colors.surface.hex(),
            foreground = colors.onSurface.hex(),
            mutedForeground = colors.onSurfaceVariant.hex(),
            scheme = if (dark) "dark" else "light",
            config = buildString {
                appendLine("cursor-color = ${colors.primary.hex()}")
                palette.forEachIndexed { index, color -> appendLine("palette = $index=${color.hex()}") }
            },
            fontSize = fontSize
        )
    }
}

private fun Color.hex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)
