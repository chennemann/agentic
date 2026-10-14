package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Trash: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Trash",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(3f, 6f)
            horizontalLineTo(21f)
        }
        lucidePath {
            moveTo(19f, 6f)
            verticalLineTo(20f)
            curveToRelative(0f, 1f, -1f, 2f, -2f, 2f)
            horizontalLineTo(7f)
            curveToRelative(-1f, 0f, -2f, -1f, -2f, -2f)
            verticalLineTo(6f)
        }
        lucidePath {
            moveTo(8f, 6f)
            verticalLineTo(4f)
            curveToRelative(0f, -1f, 1f, -2f, 2f, -2f)
            horizontalLineToRelative(4f)
            curveToRelative(1f, 0f, 2f, 1f, 2f, 2f)
            verticalLineTo(6f)
        }
        lucidePath {
            moveTo(10f, 11f)
            verticalLineTo(17f)
        }
        lucidePath {
            moveTo(14f, 11f)
            verticalLineTo(17f)
        }
    }.build()
}
