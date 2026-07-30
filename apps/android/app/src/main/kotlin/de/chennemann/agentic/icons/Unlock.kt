package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Unlock: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Unlock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(5f, 11f)
            horizontalLineTo(19f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineTo(20f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(13f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            close()
        }
        lucidePath {
            moveTo(7f, 11f)
            verticalLineTo(7f)
            arcToRelative(5f, 5f, 0f, false, true, 9.9f, -1f)
        }
    }.build()
}
