package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Sparkles: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Sparkles",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 3f)
            curveTo(12.6f, 7.8f, 14.2f, 9.4f, 19f, 10f)
            curveTo(14.2f, 10.6f, 12.6f, 12.2f, 12f, 17f)
            curveTo(11.4f, 12.2f, 9.8f, 10.6f, 5f, 10f)
            curveTo(9.8f, 9.4f, 11.4f, 7.8f, 12f, 3f)
            close()
            moveTo(19f, 3f)
            verticalLineTo(7f)
            moveTo(21f, 5f)
            horizontalLineTo(17f)
            moveTo(5f, 16f)
            verticalLineTo(21f)
            moveTo(7.5f, 18.5f)
            horizontalLineTo(2.5f)
        }
    }.build()
}
