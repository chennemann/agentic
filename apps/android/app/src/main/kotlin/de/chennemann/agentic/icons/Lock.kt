package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Lock: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Lock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(7f, 10f)
            verticalLineTo(7f)
            curveTo(7f, 4.2f, 9.2f, 2f, 12f, 2f)
            curveTo(14.8f, 2f, 17f, 4.2f, 17f, 7f)
            verticalLineTo(10f)
            moveTo(5f, 10f)
            horizontalLineTo(19f)
            curveTo(20.1f, 10f, 21f, 10.9f, 21f, 12f)
            verticalLineTo(20f)
            curveTo(21f, 21.1f, 20.1f, 22f, 19f, 22f)
            horizontalLineTo(5f)
            curveTo(3.9f, 22f, 3f, 21.1f, 3f, 20f)
            verticalLineTo(12f)
            curveTo(3f, 10.9f, 3.9f, 10f, 5f, 10f)
            close()
        }
    }.build()
}
