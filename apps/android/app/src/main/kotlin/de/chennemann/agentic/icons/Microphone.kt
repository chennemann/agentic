package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Microphone: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Microphone",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(12f, 2f)
            arcToRelative(3f, 3f, 0f, false, false, -3f, 3f)
            verticalLineToRelative(7f)
            arcToRelative(3f, 3f, 0f, false, false, 6f, 0f)
            verticalLineTo(5f)
            arcToRelative(3f, 3f, 0f, false, false, -3f, -3f)
            close()
        }
        lucidePath {
            moveTo(19f, 10f)
            verticalLineToRelative(2f)
            arcToRelative(7f, 7f, 0f, false, true, -14f, 0f)
            verticalLineToRelative(-2f)
        }
        lucidePath {
            moveTo(12f, 19f)
            verticalLineToRelative(3f)
        }
    }.build()
}
