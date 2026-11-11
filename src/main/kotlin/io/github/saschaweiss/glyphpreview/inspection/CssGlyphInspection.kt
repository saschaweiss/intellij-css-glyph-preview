package io.github.saschaweiss.glyphpreview.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import io.github.saschaweiss.glyphpreview.css.CssGlyphUtil
import io.github.saschaweiss.glyphpreview.detect.IconLibrary
import io.github.saschaweiss.glyphpreview.font.GlyphRenderer
import io.github.saschaweiss.glyphpreview.settings.GlyphSettings

/**
 * Flags a `content: "\fXXXX"` icon that won't render at the rule's resolved
 * `font-weight`, when a registered font of the SAME family contains the glyph at
 * a DIFFERENT weight. This is the classic "wrote font-weight: 900 but only the
 * regular (400) font has this glyph" mistake — the browser would show a blank
 * box. The quick-fix rewrites the weight to the one that actually renders.
 *
 * Deliberately conservative to avoid false positives:
 *  - only fires for a RECOGNISED icon library ([IconLibrary.fromCss]) — never a
 *    text font with a stray `content` value;
 *  - only when the glyph is genuinely unrenderable at the current weight but
 *    provably renderable at another registered weight. If nothing renders it
 *    (font not registered at all, or glyph truly absent) we stay silent — that
 *    case is handled by the auto-registration notification, not a red squiggle.
 */
class CssGlyphInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is CssDeclaration) inspect(element, holder)
            }
        }

    private fun inspect(declaration: CssDeclaration, holder: ProblemsHolder) {
        val codepoint = CONTENT_CP.find(declaration.text ?: return)
            ?.groupValues?.get(1)?.toIntOrNull(16) ?: return

        val family = CssGlyphUtil.resolveFamily(declaration) ?: return
        // Gate: only known icon libraries — a plain content value never triggers.
        IconLibrary.fromCss(family, null, codepoint) ?: return

        val weight = CssGlyphUtil.resolveWeight(declaration)
        val settings = GlyphSettings.getInstance()

        // Renders at the resolved weight? Then the gutter shows it — nothing to flag.
        val rendersHere = settings.candidates(family, weight)
            .any { GlyphRenderer.canDisplay(it.fontFilePath, codepoint) }
        if (rendersHere) return

        // A different registered weight of this family that DOES contain the glyph.
        val alternative = settings.candidates(family, null)
            .filter { it.weight != 0 && it.weight != weight }
            .firstOrNull { GlyphRenderer.canDisplay(it.fontFilePath, codepoint) }
            ?: return // not registered / truly absent → don't warn

        holder.registerProblem(
            declaration,
            "Glyph U+%04X isn't in \"%s\" at font-weight %d — it is at %d".format(
                codepoint, family, weight, alternative.weight,
            ),
            SetFontWeightFix(alternative.weight),
        )
    }

    /**
     * Sets the `font-weight` for the rule that holds the `content` declaration:
     * rewrites an existing weight in the same block, or inserts one right after
     * the content line. Text/offset based (no CSS PSI factory) to stay robust
     * across CSS-plugin versions — consistent with the rest of the plugin.
     */
    private class SetFontWeightFix(private val weight: Int) : LocalQuickFix {

        override fun getFamilyName(): String = "Set font-weight to $weight"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val declaration = descriptor.psiElement as? CssDeclaration ?: return
            val ruleset = PsiTreeUtil.getParentOfType(declaration, CssRuleset::class.java) ?: return
            val file = declaration.containingFile ?: return
            val pdm = PsiDocumentManager.getInstance(project)
            val doc = pdm.getDocument(file) ?: return

            val existing = EXISTING_WEIGHT.find(ruleset.text)
            if (existing != null) {
                val group = existing.groups[1]!!.range
                val from = ruleset.textRange.startOffset + group.first
                val to = ruleset.textRange.startOffset + group.last + 1
                doc.replaceString(from, to, weight.toString())
                pdm.commitDocument(doc)
                return
            }

            // No weight in this block → insert after the content declaration,
            // just past any trailing ';', matching its indentation.
            val chars = doc.charsSequence
            var insertAt = declaration.textRange.endOffset
            var p = insertAt
            while (p < doc.textLength && (chars[p] == ' ' || chars[p] == '\t')) p++
            if (p < doc.textLength && chars[p] == ';') insertAt = p + 1

            val lineStart = doc.getLineStartOffset(doc.getLineNumber(declaration.textRange.startOffset))
            val indent = doc.getText(com.intellij.openapi.util.TextRange(lineStart, declaration.textRange.startOffset))
                .takeWhile { it == ' ' || it == '\t' }
            val needsSemicolon = insertAt == 0 || chars[insertAt - 1] != ';'
            val insertion = (if (needsSemicolon) ";" else "") + "\n${indent}font-weight: $weight;"

            doc.insertString(insertAt, insertion)
            pdm.commitDocument(doc)
        }
    }

    companion object {
        private val CONTENT_CP = Regex("""content\s*:\s*["']\\([0-9A-Fa-f]+)""")
        private val EXISTING_WEIGHT = Regex("""font-weight\s*:\s*(\d{3})""")
    }
}
