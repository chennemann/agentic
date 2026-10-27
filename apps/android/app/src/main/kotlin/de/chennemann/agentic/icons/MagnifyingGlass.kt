package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.MagnifyingGlass: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "MagnifyingGlass",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(19f, 11f)
            arcTo(8f, 8f, 0f, false, true, 11f, 19f)
            arcTo(8f, 8f, 0f, false, true, 3f, 11f)
            arcTo(8f, 8f, 0f, false, true, 19f, 11f)
            close()
        }
        lucidePath {
            moveTo(21f, 21f)
            lineTo(16.7f, 16.7f)
        }
    }.build()
}
