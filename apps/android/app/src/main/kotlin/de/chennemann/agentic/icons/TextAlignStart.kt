package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.TextAlignStart: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "TextAlignStart",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(21f, 6f)
            horizontalLineTo(3f)
            moveTo(15f, 12f)
            horizontalLineTo(3f)
            moveTo(17f, 18f)
            horizontalLineTo(3f)
        }
    }.build()
}
