package echopc

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Immutable
class Accent(val primary: Color, val secondary: Color) {
    companion object {
        /** Brand violet, used when there is no album art to take a colour from. */
        val DEFAULT = Accent(Color(0xFF8B5CF6), Color(0xFF6D3FE0))
    }

    /** Lighter tint of the primary for text and icons on dark surfaces. */
    val highlight: Color get() = androidx.compose.ui.graphics.lerp(primary, Color.White, 0.28f)

    /** Text/icon colour on top of [primary]: album art can be anything from pale yellow to navy. */
    val onPrimary: Color get() = if (primary.luminance() > 0.45f) Color.Black else Color.White

    /** Darkened primary used to flood large areas (headers, Now Playing, lyrics) without glare. */
    val flood: Color get() = androidx.compose.ui.graphics.lerp(primary, Color.Black, 0.45f)
}
