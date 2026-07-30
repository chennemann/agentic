package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Pencil: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Pencil",
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
            moveTo(4f, 20f)
            horizontalLineTo(8f)
            lineTo(19.5f, 8.5f)
            curveTo(20.9f, 7.1f, 20.9f, 4.9f, 19.5f, 3.5f)
            curveTo(18.1f, 2.1f, 15.9f, 2.1f, 14.5f, 3.5f)
            lineTo(3f, 15f)
            verticalLineTo(20f)
            horizontalLineTo(4f)
            close()
            moveTo(13f, 5f)
            lineTo(18f, 10f)
        }
    }.build()
}
