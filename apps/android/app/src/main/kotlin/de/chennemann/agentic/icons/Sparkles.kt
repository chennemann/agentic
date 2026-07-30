package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Sparkles: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Sparkles",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(11.017f, 2.814f)
            arcToRelative(1f, 1f, 0f, false, true, 1.966f, 0f)
            lineToRelative(1.051f, 5.558f)
            arcToRelative(2f, 2f, 0f, false, false, 1.594f, 1.594f)
            lineToRelative(5.558f, 1.051f)
            arcToRelative(1f, 1f, 0f, false, true, 0f, 1.966f)
            lineToRelative(-5.558f, 1.051f)
            arcToRelative(2f, 2f, 0f, false, false, -1.594f, 1.594f)
            lineToRelative(-1.051f, 5.558f)
            arcToRelative(1f, 1f, 0f, false, true, -1.966f, 0f)
            lineToRelative(-1.051f, -5.558f)
            arcToRelative(2f, 2f, 0f, false, false, -1.594f, -1.594f)
            lineToRelative(-5.558f, -1.051f)
            arcToRelative(1f, 1f, 0f, false, true, 0f, -1.966f)
            lineToRelative(5.558f, -1.051f)
            arcToRelative(2f, 2f, 0f, false, false, 1.594f, -1.594f)
            close()
        }
        lucidePath {
            moveTo(19f, 3f)
            verticalLineToRelative(4f)
        }
        lucidePath {
            moveTo(21f, 5f)
            horizontalLineToRelative(-4f)
        }
        lucidePath {
            moveTo(6f, 20f)
            arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
            arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
            close()
        }
    }.build()
}
