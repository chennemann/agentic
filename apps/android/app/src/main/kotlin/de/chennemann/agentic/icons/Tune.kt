package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Tune: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Tune",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(10f, 5f)
            horizontalLineTo(3f)
        }
        lucidePath {
            moveTo(12f, 19f)
            horizontalLineTo(3f)
        }
        lucidePath {
            moveTo(14f, 3f)
            verticalLineTo(7f)
        }
        lucidePath {
            moveTo(16f, 17f)
            verticalLineTo(21f)
        }
        lucidePath {
            moveTo(21f, 12f)
            horizontalLineTo(12f)
        }
        lucidePath {
            moveTo(21f, 19f)
            horizontalLineTo(16f)
        }
        lucidePath {
            moveTo(21f, 5f)
            horizontalLineTo(14f)
        }
        lucidePath {
            moveTo(8f, 10f)
            verticalLineTo(14f)
        }
        lucidePath {
            moveTo(8f, 12f)
            horizontalLineTo(3f)
        }
    }.build()
}
