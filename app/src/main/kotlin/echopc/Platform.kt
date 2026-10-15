package echopc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

private interface DwmApi : Library {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
}

/**
 * Makes the Windows title bar dark to match the app (Windows 10 2004+ / Windows 11).
 * Harmless no-op anywhere else; any failure just leaves the normal title bar.
 */
fun applyDarkTitleBar(window: java.awt.Window) {
    if (!System.getProperty("os.name").startsWith("Windows")) return
    runCatching {
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val dwm = Native.load("dwmapi", DwmApi::class.java)
        val on = IntByReference(1)
        dwm.DwmSetWindowAttribute(hwnd, 20, on, 4) // DWMWA_USE_IMMERSIVE_DARK_MODE
        dwm.DwmSetWindowAttribute(hwnd, 19, on, 4) // same flag on early Windows 10 builds
        dwm.DwmSetWindowAttribute(hwnd, 35, IntByReference(0x0B0606), 4) // Windows 11: caption colour (BGR of #06060B)
        dwm.DwmSetWindowAttribute(hwnd, 36, IntByReference(0xF8EEEE), 4) // Windows 11: caption text colour
        // Ask Windows to repaint the frame so the change shows immediately.
        User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0004 or 0x0020 or 0x0010)
    }
}

/** The W's MUSIC icon, drawn in code: violet→cyan rounded square with a white W. */
fun renderAppIcon(size: Int): BufferedImage {
    // The W's MUSIC logo (white on black, from branding/); the drawn icon below is the fallback.
    Thread.currentThread().contextClassLoader?.getResourceAsStream("app-icon.png")?.use { stream ->
        val logo = javax.imageio.ImageIO.read(stream)
        if (logo != null) {
            val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(logo, 0, 0, size, size, null)
            g.dispose()
            return out
        }
    }
    return drawnAppIcon(size)
}

private fun drawnAppIcon(size: Int): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    val s = size.toFloat()
    val pad = s * 0.04f
    val body = RoundRectangle2D.Float(pad, pad, s - 2 * pad, s - 2 * pad, s * 0.26f, s * 0.26f)
    g.paint = GradientPaint(0f, 0f, Color(0x8B5CF6), s, s, Color(0x22D3EE))
    g.fill(body)
    // soft highlight
    g.paint = GradientPaint(0f, 0f, Color(255, 255, 255, 30), 0f, s * 0.55f, Color(255, 255, 255, 0))
    g.fill(body)
    // "W" as a sharp stroked zig-zag
    g.color = Color.WHITE
    g.stroke = BasicStroke(s * 0.095f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val l = s * 0.22f; val r = s * 0.78f; val top = s * 0.28f; val bottom = s * 0.72f
    val path = java.awt.geom.Path2D.Float()
    path.moveTo(l, top)
    path.lineTo(s * 0.36f, bottom)
    path.lineTo(s * 0.50f, s * 0.42f)
    path.lineTo(s * 0.64f, bottom)
    path.lineTo(r, top)
    g.draw(path)
    g.dispose()
    return img
}
