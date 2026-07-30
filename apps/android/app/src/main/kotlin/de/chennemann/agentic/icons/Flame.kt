package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Flame: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Flame",
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
            moveTo(12.8f, 2.5f)
            curveTo(13.3f, 6.2f, 9.2f, 7.1f, 10.4f, 10.5f)
            curveTo(8.8f, 9.7f, 8.2f, 8.3f, 8.3f, 6.8f)
            curveTo(5.7f, 9f, 4.2f, 11.8f, 4.5f, 14.8f)
            curveTo(4.9f, 18.8f, 8.1f, 21.5f, 12f, 21.5f)
            curveTo(16.4f, 21.5f, 19.5f, 18.3f, 19.5f, 14.1f)
            curveTo(19.5f, 9.8f, 16.7f, 5.7f, 12.8f, 2.5f)
            close()
            moveTo(12f, 18.8f)
            curveTo(10.3f, 18.8f, 9.2f, 17.6f, 9.2f, 16.1f)
            curveTo(9.2f, 14.7f, 10.2f, 13.7f, 11.7f, 12.5f)
            curveTo(11.8f, 14.2f, 14.4f, 14.7f, 14.4f, 16.4f)
            curveTo(14.4f, 17.8f, 13.4f, 18.8f, 12f, 18.8f)
            close()
        }
    }.build()
}
