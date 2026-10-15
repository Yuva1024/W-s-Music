package echopc

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * W's MUSIC design tokens: a neutral black/grey layout (three panels on a black window) with one
 * accent that is taken from the current album art. Nothing else in the UI is coloured.
 */
object Ws {
    val bg0 = Color(0xFF000000) // window, gaps between panels, player bar
    val surface = Color(0xFF121212) // panels
    val surfaceHi = Color(0xFF1F1F1F) // cards and rows on hover, inputs
    val surfaceTop = Color(0xFF2A2A2A) // menus, pressed, chips
    val border = Color(0xFF333333)
    val text = Color(0xFFFFFFFF)
    val muted = Color(0xFFB3B3B3)
    val faint = Color(0xFF7A7A7A)
    val danger = Color(0xFFF3727F)

    val defaultAccent = Color(0xFF8B5CF6) // violet
    val defaultAccent2 = Color(0xFF6D3FE0)
}



val LocalAccent = compositionLocalOf { Accent(Ws.defaultAccent, Ws.defaultAccent2) }

/** Figtree (OFL, bundled in resources/fonts). Falls back to the system face if the files are missing. */
val UiFont: FontFamily = runCatching {
    FontFamily(
        Font("fonts/Figtree-Regular.ttf", FontWeight.Normal),
        Font("fonts/Figtree-Medium.ttf", FontWeight.Medium),
        Font("fonts/Figtree-SemiBold.ttf", FontWeight.SemiBold),
        Font("fonts/Figtree-Bold.ttf", FontWeight.Bold),
        Font("fonts/Figtree-ExtraBold.ttf", FontWeight.ExtraBold),
    )
}.getOrDefault(FontFamily.Default)

/** Bahnschrift (ships with Windows 10/11) is kept only for the "W" brand mark. */
val BrandFont: FontFamily = run {
    val file = File(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts\\bahnschrift.ttf")
    if (file.isFile) runCatching { FontFamily(Font(file, FontWeight.Bold)) }.getOrDefault(UiFont) else UiFont
}

private fun t(size: Int, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f, lineHeight: Float = 1.35f) =
    TextStyle(fontFamily = UiFont, fontSize = size.sp, fontWeight = weight, letterSpacing = spacing.sp, lineHeight = (size * lineHeight).sp)

// Scale: 11 · 12 · 13 · 14 · 16 · 20 · 24 · 32 · 72. Large titles are tight (negative tracking), small text is plain.
private val WsTypography = Typography(
    displayLarge = t(72, FontWeight.ExtraBold, -2f, 1.05f),
    displayMedium = t(48, FontWeight.ExtraBold, -1.2f, 1.08f),
    displaySmall = t(36, FontWeight.ExtraBold, -0.8f, 1.1f),
    headlineLarge = t(32, FontWeight.Bold, -0.6f, 1.15f),
    headlineMedium = t(24, FontWeight.Bold, -0.3f, 1.2f),
    titleLarge = t(20, FontWeight.Bold, -0.1f),
    titleMedium = t(16, FontWeight.SemiBold),
    titleSmall = t(14, FontWeight.SemiBold),
    bodyLarge = t(16),
    bodyMedium = t(14),
    bodySmall = t(13),
    labelLarge = t(14, FontWeight.Bold),
    labelMedium = t(12, FontWeight.SemiBold),
    labelSmall = t(11, FontWeight.Medium),
)

@Composable
fun WsTheme(accent: Accent, content: @Composable () -> Unit) {
    // The accent switches instantly with the song; animating it recomposed the whole window for ~40 frames.
    val scheme = darkColorScheme(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primary.copy(alpha = 0.22f),
        onPrimaryContainer = Ws.text,
        secondary = accent.secondary,
        background = Ws.bg0,
        onBackground = Ws.text,
        surface = Ws.surface,
        onSurface = Ws.text,
        surfaceVariant = Ws.surfaceHi,
        onSurfaceVariant = Ws.muted,
        outline = Ws.border,
        error = Ws.danger,
    )
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = WsTypography, content = content)
    }
}
