package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Star: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Star",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveToRelative(233f, 840f)
            lineToRelative(65f, -281f)
            lineTo(80f, 370f)
            lineToRelative(288f, -25f)
            lineToRelative(112f, -265f)
            lineToRelative(112f, 265f)
            lineToRelative(288f, 25f)
            lineToRelative(-218f, 189f)
            lineToRelative(65f, 281f)
            lineToRelative(-247f, -149f)
            lineToRelative(-247f, 149f)
            close()
        }
    }.build()
}

