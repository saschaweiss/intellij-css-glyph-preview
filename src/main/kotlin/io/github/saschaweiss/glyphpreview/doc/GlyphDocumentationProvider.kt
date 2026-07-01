package io.github.saschaweiss.glyphpreview.doc

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ui.JBUI
import io.github.saschaweiss.glyphpreview.css.CssGlyphUtil
import io.github.saschaweiss.glyphpreview.font.GlyphRenderer
import io.github.saschaweiss.glyphpreview.html.HtmlIconResolver
import io.github.saschaweiss.glyphpreview.settings.GlyphSettings
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * Quick documentation (View | Quick Documentation, or hover when enabled) that
 * shows a larger preview of the icon under the cursor — for CSS
 * `content: "\fXXXX"` and HTML `class="fas fa-pencil"`.
 *
 * Uses the classic DocumentationProvider API and renders the glyph into a cached
 * PNG referenced via a file:// URL. Registered separately for CSS and HTML; it
 * touches nothing in the existing features.
 */
class GlyphDocumentationProvider : AbstractDocumentationProvider() {

    private data class Glyph(val label: String, val fontPath: String, val codepoint: Int)

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        val element = contextElement ?: return null
        return if (resolve(element) != null) element else null
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val glyph = (element?.let { resolve(it) }) ?: (originalElement?.let { resolve(it) }) ?: return null
        val icon = GlyphRenderer.icon(glyph.fontPath, glyph.codepoint, JBUI.scale(48)) ?: return null
        val url = pngUrl(icon, glyph) ?: return null
        val codepoint = "U+%04X".format(glyph.codepoint)
        return """<div style="padding:5px"><img src="$url" width="48" height="48"/>""" +
            """<br/><b>${glyph.label}</b>&nbsp;<code>$codepoint</code></div>"""
    }

    private fun resolve(element: PsiElement): Glyph? {
        // CSS / SCSS:  content: "\fXXXX"
        val decl = CssGlyphUtil.declarationOf(element)
        if (decl != null && CssGlyphUtil.isContentDeclaration(decl.text)) {
            val cp = CONTENT_CP.find(decl.text)?.groupValues?.get(1)?.toIntOrNull(16) ?: return null
            val family = CssGlyphUtil.resolveFamily(element)
            val weight = CssGlyphUtil.resolveWeight(element)
            val entry = GlyphSettings.getInstance().candidates(family, weight)
                .firstOrNull { GlyphRenderer.icon(it.fontFilePath, cp) != null } ?: return null
            return Glyph("U+%04X".format(cp), entry.fontFilePath, cp)
        }
        // HTML:  class="fas fa-pencil"
        val attributeValue = PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java, false)
        val attribute = attributeValue?.parent as? XmlAttribute
        if (attributeValue != null && attribute != null && attribute.name.equals("class", ignoreCase = true)) {
            val (name, cp) = HtmlIconResolver.firstCodepoint(attributeValue.value) ?: return null
            val weight = HtmlIconResolver.weightFromClasses(attributeValue.value)
            val fonts = GlyphSettings.getInstance().fonts
            val ordered = if (weight == null) fonts else fonts.sortedByDescending { it.weight == weight }
            val entry = ordered.firstOrNull { GlyphRenderer.icon(it.fontFilePath, cp) != null } ?: return null
            return Glyph(name, entry.fontFilePath, cp)
        }
        return null
    }

    private fun pngUrl(icon: Icon, glyph: Glyph): String? = runCatching {
        val dir = File(PathManager.getConfigPath(), "css-glyph-preview/hover").apply { mkdirs() }
        val safeFont = File(glyph.fontPath).nameWithoutExtension.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "${safeFont}_${glyph.codepoint.toString(16)}.png")
        if (!file.exists()) {
            val img = BufferedImage(
                icon.iconWidth.coerceAtLeast(1),
                icon.iconHeight.coerceAtLeast(1),
                BufferedImage.TYPE_INT_ARGB,
            )
            val g = img.createGraphics()
            icon.paintIcon(null, g, 0, 0)
            g.dispose()
            ImageIO.write(img, "png", file)
        }
        file.toURI().toString()
    }.getOrNull()

    private companion object {
        val CONTENT_CP = Regex("""content\s*:\s*["']\\([0-9A-Fa-f]+)""")
    }
}
