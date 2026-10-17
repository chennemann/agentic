package de.chennemann.agentic.ui.review

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import de.chennemann.agentic.streamingmarkdown.CodeTokenKind
import de.chennemann.agentic.ui.code.codeSyntaxStyles
import de.chennemann.agentic.ui.theme.LocalCodeScale
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ReviewAppearance(
    val theme: String,
    val style: String,
    val tokenColors: Map<CodeTokenKind, String>,
    val italicTokens: Set<CodeTokenKind>
)

@Composable
internal fun rememberReviewAppearance(wrap: Boolean): ReviewAppearance {
    val colors = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val interfaceScale = density.density / (configuration.densityDpi / 160f)
    val textScale = interfaceScale * density.fontScale
    val codeScale = textScale * LocalCodeScale.current
    return remember(colors, wrap, interfaceScale, textScale, codeScale) {
        val dark = colors.surface.luminance() < 0.5f
        val syntax = codeSyntaxStyles(dark)
        val green = if (dark) Color(0xFFA0CC5B) else Color(0xFF326B1D)
        val red = colors.error
        ReviewAppearance(
            theme = buildJsonObject {
                put("background", colors.surface.hex())
                put("text", colors.onSurface.hex())
                put("mutedText", colors.onSurfaceVariant.hex())
                put("headerBackground", colors.surfaceContainer.hex())
                put("border", colors.outlineVariant.hex())
                put("hunkBackground", lerp(colors.surface, colors.primary, 0.08f).hex())
                put("hunkText", colors.primary.hex())
                put("addBackground", lerp(colors.surface, green, 0.12f).hex())
                put("deleteBackground", lerp(colors.surface, red, 0.12f).hex())
                put("addBar", green.hex())
                put("deleteBar", red.hex())
                put("addText", green.hex())
                put("deleteText", red.hex())
            }.toString(),
            style = buildJsonObject {
                put("wordWrap", wrap)
                put("rowHeight", 22f * codeScale)
                put("gutterWidth", 66f * codeScale)
                put("codePadding", 10f * interfaceScale)
                put("changeBarWidth", 3f * interfaceScale)
                put("fileHeaderHeight", 48f * textScale)
                put("fileHeaderHorizontalPadding", 10f * interfaceScale)
                put("codeFontSize", 13f * codeScale)
                put("lineNumberFontSize", 10f * codeScale)
                put("hunkFontSize", 11f * codeScale)
                put("fileHeaderFontSize", 12f * textScale)
                put("fileHeaderMetaFontSize", 10f * textScale)
                put("fileHeaderSubtextFontSize", 11f * textScale)
            }.toString(),
            tokenColors = CodeTokenKind.entries.associateWith { (syntax[it]?.color ?: colors.onSurface).hex() },
            italicTokens = syntax.filterValues { it.italic }.keys
        )
    }
}

private fun Color.hex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)
