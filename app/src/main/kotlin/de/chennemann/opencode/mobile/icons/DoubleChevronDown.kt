package de.chennemann.opencode.mobile.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp


val Icons.DoubleChevronDown: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "DoubleChevronDown",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(480f, 760f)
            lineTo(240f, 520f)
            lineToRelative(56f, -56f)
            lineToRelative(184f, 183f)
            lineToRelative(184f, -183f)
            lineToRelative(56f, 56f)
            lineToRelative(-240f, 240f)
            close()
            moveTo(480f, 520f)
            lineTo(240f, 280f)
            lineToRelative(56f, -56f)
            lineToRelative(184f, 183f)
            lineToRelative(184f, -183f)
            lineToRelative(56f, 56f)
            lineToRelative(-240f, 240f)
            close()
        }
    }.build()
}
