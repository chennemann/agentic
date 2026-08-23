package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Paperclip: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Paperclip",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(16f, 6f)
            lineTo(7.586f, 14.586f)
            arcToRelative(2f, 2f, 0f, false, false, 2.829f, 2.829f)
            lineTo(18.829f, 8.829f)
            arcToRelative(4f, 4f, 0f, true, false, -5.657f, -5.657f)
            lineTo(4.793f, 11.723f)
            arcToRelative(6f, 6f, 0f, true, false, 8.485f, 8.485f)
            lineTo(21.657f, 11.657f)
        }
    }.build()
}
