package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Flame: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Flame",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(12f, 3f)
            quadTo(13f, 7f, 16f, 9.5f)
            quadTo(19f, 12f, 19f, 15f)
            arcToRelative(1f, 1f, 0f, false, true, -14f, 0f)
            arcToRelative(5f, 5f, 0f, false, true, 1f, -3f)
            arcToRelative(1f, 1f, 0f, false, false, 5f, 0f)
            curveToRelative(0f, -2f, -1.5f, -3f, -1.5f, -5f)
            quadToRelative(0f, -2f, 2.5f, -4f)
        }
    }.build()
}
