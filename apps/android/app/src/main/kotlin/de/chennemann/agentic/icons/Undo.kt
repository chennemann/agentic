package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Undo: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Undo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(9f, 14f)
            lineTo(4f, 9f)
            lineTo(9f, 4f)
        }
        lucidePath {
            moveTo(4f, 9f)
            horizontalLineToRelative(10.5f)
            arcToRelative(5.5f, 5.5f, 0f, false, true, 5.5f, 5.5f)
            arcToRelative(5.5f, 5.5f, 0f, false, true, -5.5f, 5.5f)
            horizontalLineTo(11f)
        }
    }.build()
}
