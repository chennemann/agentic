package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Tune: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Tune",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 7f)
            horizontalLineTo(9f)
            moveTo(15f, 7f)
            horizontalLineTo(20f)
            moveTo(4f, 17f)
            horizontalLineTo(13f)
            moveTo(19f, 17f)
            horizontalLineTo(20f)
            moveTo(12f, 4f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, 6f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, -6f)
            moveTo(16f, 14f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, 6f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, -6f)
        }
    }.build()
}
