package io.github.saschaweiss.glyphpreview.html

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import io.github.saschaweiss.glyphpreview.detect.AutoRegisterService

/**
 * Draws the matching glyph in the gutter next to `<i class="fas fa-pencil">`.
 *
 * Fires on the single attribute-value token (leaf) to avoid duplicate markers.
 * Also works in HTML injected into template languages (e.g. Twig), since those
 * class attributes are still HTML XML PSI.
 */
class HtmlGlyphLineMarkerProvider : LineMarkerProvider {

    // Fast pass does nothing; resolution runs in the slow pass to keep editing snappy.
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        for (element in elements) markerFor(element)?.let { result.add(it) }
    }

    private fun markerFor(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is XmlToken || element.tokenType != XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN) return null

        val attributeValue = element.parent as? XmlAttributeValue ?: return null
        val attribute = attributeValue.parent as? XmlAttribute ?: return null
        if (!attribute.name.equals("class", ignoreCase = true)) return null

        val classes = attributeValue.value
        val rendered = HtmlIconResolver.firstRenderable(classes)
        if (rendered == null) {
            // Recognised icon class we can't render → offer auto-registration.
            HtmlIconResolver.usageFor(classes)?.let {
                AutoRegisterService.getInstance(element.project).consider(it)
            }
            return null
        }
        val (iconName, icon) = rendered

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { "Icon: $iconName" },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { "Icon $iconName" },
        )
    }
}
