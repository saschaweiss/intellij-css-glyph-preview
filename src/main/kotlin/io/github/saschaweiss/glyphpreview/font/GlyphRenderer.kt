package io.github.saschaweiss.glyphpreview.font

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * Loads icon fonts and rasterizes single glyphs (by Unicode codepoint) into
 * Swing [Icon]s for the gutter and the completion popup.
 *
 * Two caches, because both are hot paths:
 *  - [fontCache]  keyed by absolute file path  (AWT Font instances)
 *  - [iconCache]  keyed by path + codepoint + size  (rendered glyphs)
 *
 * IMPORTANT: java.awt.Font.createFont reads TrueType/OpenType (.ttf/.otf) only —
 * NOT .woff/.woff2. The user must register a .ttf/.otf in the settings.
 */
object GlyphRenderer {

    private val log = logger<GlyphRenderer>()
    // Non-null value type: ConcurrentHashMap cannot store null. Failed loads are
    // simply not cached (and retried), instead of crashing on a null put.
    private val fontCache = ConcurrentHashMap<String, Font>()
    private val iconCache = ConcurrentHashMap<String, Icon>()

    /** The font's own family name, used to pre-fill the settings row. */
    fun familyNameOf(path: String): String? = baseFont(path)?.family

    /**
     * Renders [codepoint] (an int, e.g. 0xf35d) from the font at [fontPath].
     * Returns null if the font can't be loaded or doesn't contain the glyph.
     */
    fun icon(fontPath: String, codepoint: Int, size: Int = JBUI.scale(13)): Icon? {
        val key = "$fontPath#${codepoint.toString(16)}#$size"
        iconCache[key]?.let { return it }

        val font = baseFont(fontPath) ?: return null
        if (!font.canDisplay(codepoint)) return null

        val icon = render(font, codepoint, size)
        iconCache[key] = icon
        return icon
    }

    private fun baseFont(path: String): Font? {
        fontCache[path]?.let { return it }
        val font = runCatching {
            Font.createFont(Font.TRUETYPE_FONT, File(path))
        }.onFailure { log.warn("Could not load font: $path", it) }.getOrNull() ?: return null
        fontCache[path] = font
        return font
    }

    private fun render(font: Font, codepoint: Int, size: Int): Icon {
        val text = String(Character.toChars(codepoint))
        val sized = font.deriveFont(size.toFloat())

        // Two passes: measure, then draw centered.
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        probe.font = sized
        val fm = probe.fontMetrics
        val w = maxOf(fm.stringWidth(text), size)
        val h = maxOf(fm.height, size)
        probe.dispose()

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.font = sized
        // Theme-aware foreground so the glyph stays visible in light & dark themes.
        g.color = JBColor.foreground()
        val x = (w - fm.stringWidth(text)) / 2
        val y = (h - fm.height) / 2 + fm.ascent
        g.drawString(text, x, y)
        g.dispose()

        return ImageIcon(img)
    }

    fun clearCache() {
        fontCache.clear()
        iconCache.clear()
    }
}
