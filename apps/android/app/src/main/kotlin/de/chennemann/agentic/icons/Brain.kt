package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Brain: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Brain",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(12f, 18f)
            verticalLineTo(5f)
        }
        lucidePath {
            moveTo(15f, 13f)
            arcToRelative(4.17f, 4.17f, 0f, false, true, -3f, -4f)
            arcToRelative(4.17f, 4.17f, 0f, false, true, -3f, 4f)
        }
        lucidePath {
            moveTo(17.598f, 6.5f)
            arcTo(3f, 3f, 0f, true, false, 12f, 5f)
            arcToRelative(3f, 3f, 0f, true, false, -5.598f, 1.5f)
        }
        lucidePath {
            moveTo(17.997f, 5.125f)
            arcToRelative(4f, 4f, 0f, false, true, 2.526f, 5.77f)
        }
        lucidePath {
            moveTo(18f, 18f)
            arcToRelative(4f, 4f, 0f, false, false, 2f, -7.464f)
        }
        lucidePath {
            moveTo(19.967f, 17.483f)
            arcTo(4f, 4f, 0f, true, true, 12f, 18f)
            arcToRelative(4f, 4f, 0f, true, true, -7.967f, -0.517f)
        }
        lucidePath {
            moveTo(6f, 18f)
            arcToRelative(4f, 4f, 0f, false, true, -2f, -7.464f)
        }
        lucidePath {
            moveTo(6.003f, 5.125f)
            arcToRelative(4f, 4f, 0f, false, false, -2.526f, 5.77f)
        }
    }.build()
}
