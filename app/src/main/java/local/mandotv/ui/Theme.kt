package local.mandotv.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta del mando: cuerpo oscuro, teclas grafito y acentos de colores. */
object Palette {
    val background = Color(0xFF0B0C0F)
    val body = Color(0xFF15171C)
    val bodyEdge = Color(0xFF23262D)
    val key = Color(0xFF262A32)
    val keyPressed = Color(0xFF3A404B)
    val keyText = Color(0xFFE6E9EF)
    val keySubtle = Color(0xFF9AA3B2)
    val power = Color(0xFFE5484D)
    val red = Color(0xFFE5484D)
    val green = Color(0xFF30A46C)
    val yellow = Color(0xFFF5D90A)
    val blue = Color(0xFF3E63DD)
    val accent = Color(0xFF7BA7FF)
    val ok = Color(0xFF30A46C)
    val warn = Color(0xFFF5A524)
    val error = Color(0xFFE5484D)
    val idle = Color(0xFF6B7280)
}

private val scheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF0B1A33),
    secondary = Palette.keySubtle,
    background = Palette.background,
    onBackground = Palette.keyText,
    surface = Palette.body,
    onSurface = Palette.keyText,
    surfaceVariant = Palette.key,
    onSurfaceVariant = Palette.keySubtle,
    surfaceContainer = Palette.body,
    surfaceContainerHigh = Palette.bodyEdge,
    surfaceContainerLow = Palette.background,
    outline = Palette.bodyEdge,
    error = Palette.error,
)

@Composable
fun MandoTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme) {
    // Texto e iconos claros por defecto (la app no usa Surface en todas las pantallas).
    CompositionLocalProvider(LocalContentColor provides Palette.keyText, content = content)
}
