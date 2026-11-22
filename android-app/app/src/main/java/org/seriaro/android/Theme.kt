package org.seriaro.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.seriaro.core.ThemeMode
import org.seriaro.core.ThemeSettings

@Composable
internal fun useDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

@Composable
internal fun SeriesTheme(settings: ThemeSettings, darkAccentText: Boolean, content: @Composable () -> Unit) {
    val accent = Color(0xFF000000 or settings.accentHex.removePrefix("#").toLong(16))
    val onAccent = if (darkAccentText) Color.Black else Color.White
    val amoled = settings.mode == ThemeMode.AMOLED
    val colors = if (useDarkTheme(settings.mode)) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent,
            onPrimaryContainer = onAccent,
            secondaryContainer = Color(0xFF27272A),
            onSecondaryContainer = Color(0xFFFAFAFA),
            background = if (amoled) Color.Black else Color(0xFF18181B),
            onBackground = Color(0xFFFAFAFA),
            surface = if (amoled) Color.Black else Color(0xFF18181B),
            surfaceContainer = if (amoled) Color.Black else Color(0xFF202023),
            onSurface = Color(0xFFFAFAFA),
            onSurfaceVariant = Color(0xFFD4D4D8),
            outline = Color(0xFF71717A),
            outlineVariant = Color(0xFF3F3F46),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent,
            onPrimaryContainer = onAccent,
            secondaryContainer = Color(0xFFE4E4E7),
            onSecondaryContainer = Color(0xFF18181B),
            background = Color(0xFFFAFAFA),
            onBackground = Color(0xFF18181B),
            surface = Color(0xFFFAFAFA),
            surfaceContainer = Color.White,
            onSurface = Color(0xFF18181B),
            onSurfaceVariant = Color(0xFF52525B),
            outline = Color(0xFF71717A),
            outlineVariant = Color(0xFFD4D4D8),
        )
    }
    val square = RoundedCornerShape(0)
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(square, square, square, square, square),
        content = content,
    )
}
