package de.chennemann.agentic.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val Icons.Play: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Play",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(6f, 4f)
            lineTo(20f, 12f)
            lineTo(6f, 20f)
            close()
        }
    }.build()
}

val Icons.Pause: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(7f, 4f)
            verticalLineTo(20f)
            moveTo(17f, 4f)
            verticalLineTo(20f)
        }
    }.build()
}

val Icons.Share: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Share",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(4f, 12f)
            verticalLineToRelative(8f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(-8f)
            moveTo(16f, 6f)
            lineTo(12f, 2f)
            lineTo(8f, 6f)
            moveTo(12f, 2f)
            verticalLineToRelative(13f)
        }
    }.build()
}

val Icons.ExternalLink: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "ExternalLink",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        lucidePath {
            moveTo(15f, 3f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(6f)
            moveTo(10f, 14f)
            lineTo(21f, 3f)
            moveTo(18f, 13f)
            verticalLineToRelative(6f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(8f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(6f)
        }
    }.build()
}
