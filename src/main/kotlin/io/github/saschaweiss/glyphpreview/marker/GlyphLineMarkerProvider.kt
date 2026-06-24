package io.github.saschaweiss.glyphpreview.marker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import io.github.saschaweiss.glyphpreview.css.CssGlyphUtil
import io.github.saschaweiss.glyphpreview.font.GlyphRenderer
import io.github.saschaweiss.glyphpreview.settings.GlyphSettings

/**
 * Draws the matching glyph in the gutter next to each `content: "\fXXXX"`.
 *
 * Per the platform guidance, markers are produced only for leaf elements — here
 * the quoted string token that actually holds the codepoint — to avoid duplicate
 * markers on the parent declaration/ruleset.
 */
class GlyphLineMarkerProvider : LineMarkerProvider {

    // The string literal leaf, e.g.  "\f35d"  or  '\f35d'
    private val tokenRegex = Regex("""^["']\\([0-9A-Fa-f]+)["']$""")

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null // leaves only

        val match = tokenRegex.find(element.text.trim()) ?: return null
        val codepoint = match.groupValues[1].toIntOrNull(16) ?: return null

        // Make sure the token really belongs to a `content:` declaration.
        val decl = CssGlyphUtil.declarationOf(element) ?: return null
        if (!CssGlyphUtil.isContentDeclaration(decl.text)) return null

        val family = CssGlyphUtil.resolveFamily(element)
        val weight = CssGlyphUtil.resolveWeight(element)
        val candidates = GlyphSettings.getInstance().candidates(family, weight)

        // Weight-preferred, but fall back to any same-family font that can render
        // the glyph (e.g. solid for a solid-only icon written without weight 900).
        val (entry, icon) = candidates.firstNotNullOfOrNull { e ->
            GlyphRenderer.icon(e.fontFilePath, codepoint)?.let { e to it }
        } ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { "%s  U+%04X".format(family ?: entry.fontFamily, codepoint) },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { "Icon glyph U+%04X".format(codepoint) },
        )
    }
}
