package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Copy: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Copy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(10f, 8f)
            horizontalLineTo(20f)
            curveTo(21.1f, 8f, 22f, 8.9f, 22f, 10f)
            verticalLineTo(20f)
            curveTo(22f, 21.1f, 21.1f, 22f, 20f, 22f)
            horizontalLineTo(10f)
            curveTo(8.9f, 22f, 8f, 21.1f, 8f, 20f)
            verticalLineTo(10f)
            curveTo(8f, 8.9f, 8.9f, 8f, 10f, 8f)
            close()
        }
        lucidePath {
            moveTo(4f, 16f)
            curveTo(2.9f, 16f, 2f, 15.1f, 2f, 14f)
            verticalLineTo(4f)
            curveTo(2f, 2.9f, 2.9f, 2f, 4f, 2f)
            horizontalLineTo(14f)
            curveTo(15.1f, 2f, 16f, 2.9f, 16f, 4f)
        }
    }.build()
}
