package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Archive: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Archive",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 3f)
            lineTo(21f, 3f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 22f, 4f)
            lineTo(22f, 7f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 21f, 8f)
            lineTo(3f, 8f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 7f)
            lineTo(2f, 4f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 3f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 8f)
            verticalLineToRelative(11f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2f, 2f)
            horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2f, -2f)
            verticalLineTo(8f)
            moveToRelative(-10f, 4f)
            horizontalLineToRelative(4f)
        }
    }.build()
}
