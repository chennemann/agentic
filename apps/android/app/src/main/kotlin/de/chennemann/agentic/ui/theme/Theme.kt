package de.chennemann.agentic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import de.chennemann.agentic.domain.preferences.AccentPreference

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


)

@Composable
fun MobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AccentPreference = AccentPreference.DEFAULT,
    content: @Composable () -> Unit
) {
    val baseColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = baseColorScheme.withAccent(accent, darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

private data class AccentColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

private fun androidx.compose.material3.ColorScheme.withAccent(
    accent: AccentPreference,
    darkTheme: Boolean,
): androidx.compose.material3.ColorScheme {
    if (accent == AccentPreference.DEFAULT) return this
    val colors = accent.colors(darkTheme)
    return copy(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.primaryContainer,
        onPrimaryContainer = colors.onPrimaryContainer,
        inversePrimary = colors.primary,
    )
}

private fun AccentPreference.colors(darkTheme: Boolean): AccentColors = when (this) {
    AccentPreference.DEFAULT -> error("Default accent uses the unchanged base color scheme")
    AccentPreference.BLUE -> if (darkTheme) {
        AccentColors(Color(0xFFA9C7FF), Color(0xFF003063), Color(0xFF214777), Color(0xFFD6E3FF))
    } else {
        AccentColors(Color(0xFF3F5F90), Color.White, Color(0xFFD6E3FF), Color(0xFF001B3D))
    }
    AccentPreference.PURPLE -> if (darkTheme) {
        AccentColors(Color(0xFFD5BAFF), Color(0xFF3F2768), Color(0xFF573E80), Color(0xFFEADDFF))
    } else {
        AccentColors(Color(0xFF7055A0), Color.White, Color(0xFFEADDFF), Color(0xFF281344))
    }
    AccentPreference.ORANGE -> if (darkTheme) {
        AccentColors(Color(0xFFFFB868), Color(0xFF492900), Color(0xFF693C00), Color(0xFFFFDDB8))
    } else {
        AccentColors(Color(0xFF8A5100), Color.White, Color(0xFFFFDDB8), Color(0xFF2C1600))
    }
    AccentPreference.PINK -> if (darkTheme) {
        AccentColors(Color(0xFFFFB0C8), Color(0xFF650033), Color(0xFF88134B), Color(0xFFFFD9E2))
    } else {
        AccentColors(Color(0xFFA83C65), Color.White, Color(0xFFFFD9E2), Color(0xFF3E001D))
    }
    AccentPreference.TEAL -> if (darkTheme) {
        AccentColors(Color(0xFF53DBC9), Color(0xFF003731), Color(0xFF005048), Color(0xFF75F8E5))
    } else {
        AccentColors(Color(0xFF006B5F), Color.White, Color(0xFF75F8E5), Color(0xFF00201C))
    }
}

fun AccentPreference.previewColor(): Color = when (this) {
    AccentPreference.DEFAULT -> Color(0xFFBFE13B)
    AccentPreference.BLUE -> Color(0xFF79A9F5)
    AccentPreference.PURPLE -> Color(0xFFB69BE8)
    AccentPreference.ORANGE -> Color(0xFFE99A45)
    AccentPreference.PINK -> Color(0xFFE67DA1)
    AccentPreference.TEAL -> Color(0xFF49BFAF)
}
