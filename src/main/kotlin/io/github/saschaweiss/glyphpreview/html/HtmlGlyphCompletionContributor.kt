package io.github.saschaweiss.glyphpreview.html

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext
import javax.swing.Icon

/**
 * Adds the glyph preview to the FontAwesome class suggestions PhpStorm already
 * offers inside `class="…"`. Instead of contributing its own (duplicate) entries,
 * it intercepts the remaining contributors' results and DECORATES the `fa-*`
 * items with the rendered icon. Non-icon entries pass through unchanged.
 *
 * Registered with order="first" so the platform's HTML class contributor counts
 * as a "remaining" contributor we can wrap.
 */
class HtmlGlyphCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val position = parameters.position
            val attributeValue = PsiTreeUtil.getParentOfType(position, XmlAttributeValue::class.java) ?: return
            val attribute = attributeValue.parent as? XmlAttribute ?: return
            if (!attribute.name.equals("class", ignoreCase = true)) return

            val names = HtmlIconResolver.nameToCodepoint()
            if (names.isEmpty()) return
            val weight = HtmlIconResolver.weightFromClasses(attributeValue.value)

            result.runRemainingContributors(parameters) { completionResult ->
                val element = completionResult.lookupElement
                val codepoint = names[element.lookupString]
                val decorated = if (codepoint != null) {
                    HtmlIconResolver.renderCodepoint(codepoint, weight)?.let { withIcon(element, it) } ?: element
                } else {
                    element
                }
                result.passResult(completionResult.withLookupElement(decorated))
            }
        }

        private fun withIcon(element: LookupElement, icon: Icon): LookupElement =
            object : LookupElementDecorator<LookupElement>(element) {
                override fun renderElement(presentation: LookupElementPresentation) {
                    super.renderElement(presentation)
                    presentation.icon = icon
                }
            }
    }
}
