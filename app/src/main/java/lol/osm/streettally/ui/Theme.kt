package lol.osm.streettally.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Street Tally color tokens from brand/colors.json (Juno's proposal, 2026-10-10).
private val Light = lightColorScheme(
    primary = Color(0xFFA84F00), // accentText: text-safe amber
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFF5A524), // accent
    onSecondary = Color(0xFF14213D),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14213D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14213D),
    surfaceVariant = Color(0xFFF4F5F7),
    onSurfaceVariant = Color(0xFF4A5468),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFBBF24),
    onPrimary = Color(0xFF0B1220),
    secondary = Color(0xFFF5A524),
    onSecondary = Color(0xFF0B1220),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF16213A),
    onSurfaceVariant = Color(0xFFA7B0C0),
)

@Composable
fun StreetTallyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
