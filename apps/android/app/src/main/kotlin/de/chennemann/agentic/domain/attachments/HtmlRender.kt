package de.chennemann.agentic.domain.attachments

import kotlin.math.roundToInt

/** A published, immutable page. Heights are CSS pixels, independent of display density. */
data class HtmlRenderPage(
    val attachmentId: String,
    val title: String,
    val height: Int,
    val heights: List<Pair<Int, Int>> = emptyList(),
) {
    fun frameHeight(width: Int, contentHeight: Int? = null): Int {
        // Published frame sizes estimate the loading row. Inline pages use their live height,
        // including pages intentionally capped for the desktop client's scrolling frame.
        return (contentHeight ?: if (heights.isEmpty()) height else measuredHeight(width)).coerceAtLeast(1)
    }

    private fun measuredHeight(width: Int): Int {
        val high = heights.indexOfFirst { it.first >= width }.takeIf { it >= 0 } ?: heights.lastIndex
        val low = if (heights[high].first == width) high else (high - 1).coerceAtLeast(0)
        return maxOf(heights[low].second, heights[high].second)
    }
}

internal fun Double.htmlFramePixels(): Int = coerceIn(80.0, 2000.0).roundToInt()
