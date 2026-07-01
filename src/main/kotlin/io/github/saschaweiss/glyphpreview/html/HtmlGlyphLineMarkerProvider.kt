package io.github.saschaweiss.glyphpreview.html

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType

/**
 * Draws the matching glyph in the gutter next to `<i class="fas fa-pencil">`.
 *
 * Fires on the single attribute-value token (leaf) to avoid duplicate markers.
 * Also works in HTML injected into template languages (e.g. Twig), since those
 * class attributes are still HTML XML PSI.
 */
class HtmlGlyphLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is XmlToken || element.tokenType != XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN) return null

        val attributeValue = element.parent as? XmlAttributeValue ?: return null
        val attribute = attributeValue.parent as? XmlAttribute ?: return null
        if (!attribute.name.equals("class", ignoreCase = true)) return null

        val (iconName, icon) = HtmlIconResolver.firstRenderable(attributeValue.value) ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { "FontAwesome: $iconName" },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { "FontAwesome icon $iconName" },
        )
    }
}
