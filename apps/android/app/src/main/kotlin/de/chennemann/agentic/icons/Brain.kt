package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Brain: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Brain",
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
            moveTo(12f, 5.5f)
            curveTo(11.5f, 3.2f, 8.4f, 2.8f, 7.2f, 5f)
            curveTo(4.9f, 4.9f, 3.8f, 7.3f, 5f, 9f)
            curveTo(2.7f, 10.2f, 3f, 13.3f, 5.1f, 14.2f)
            curveTo(4.3f, 16.8f, 6.2f, 19f, 8.5f, 18.6f)
            curveTo(9.5f, 20.7f, 12f, 19.7f, 12f, 17.5f)
            close()
            moveTo(12f, 5.5f)
            curveTo(12.5f, 3.2f, 15.6f, 2.8f, 16.8f, 5f)
            curveTo(19.1f, 4.9f, 20.2f, 7.3f, 19f, 9f)
            curveTo(21.3f, 10.2f, 21f, 13.3f, 18.9f, 14.2f)
            curveTo(19.7f, 16.8f, 17.8f, 19f, 15.5f, 18.6f)
            curveTo(14.5f, 20.7f, 12f, 19.7f, 12f, 17.5f)
            close()
            moveTo(8f, 8f)
            curveTo(9f, 8.2f, 9.7f, 9f, 9.8f, 10f)
            moveTo(16f, 8f)
            curveTo(15f, 8.2f, 14.3f, 9f, 14.2f, 10f)
            moveTo(7.2f, 14f)
            curveTo(8.4f, 13.4f, 9.6f, 13.8f, 10.1f, 15f)
            moveTo(16.8f, 14f)
            curveTo(15.6f, 13.4f, 14.4f, 13.8f, 13.9f, 15f)
        }
    }.build()
}
