package de.chennemann.opencode.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBFE13B),
    onPrimary = Color(0xFF1A2400),
    primaryContainer = Color(0xFF2D3D00),
    onPrimaryContainer = Color(0xFFE8FF9A),
    secondary = Color(0xFFA0CC5B),
    onSecondary = Color(0xFF172900),
    secondaryContainer = Color(0xFF223B00),
    onSecondaryContainer = Color(0xFFD5F3A0),
    tertiary = Color(0xFF7FCF9E),
    onTertiary = Color(0xFF003921),
    tertiaryContainer = Color(0xFF1F5032),
    onTertiaryContainer = Color(0xFFABF7C1),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEAEAEA),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEAEAEA),
    surfaceVariant = Color(0xFF171A20),
    onSurfaceVariant = Color(0xFFC1C7D0),
    outline = Color(0xFF8B9199),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
     */
)

@Composable
fun MobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
