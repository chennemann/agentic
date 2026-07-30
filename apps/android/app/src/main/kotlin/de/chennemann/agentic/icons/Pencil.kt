package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Pencil: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Pencil",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(21.174f, 6.812f)
            arcToRelative(1f, 1f, 0f, false, false, -3.986f, -3.987f)
            lineTo(3.842f, 16.174f)
            arcToRelative(2f, 2f, 0f, false, false, -0.5f, 0.83f)
            lineTo(2.021f, 21.356f)
            arcToRelative(0.5f, 0.5f, 0f, false, false, 0.623f, 0.622f)
            lineTo(6.997f, 20.658f)
            arcToRelative(2f, 2f, 0f, false, false, 0.83f, -0.497f)
            close()
        }
        lucidePath {
            moveTo(15f, 5f)
            lineTo(19f, 9f)
        }
    }.build()
}
