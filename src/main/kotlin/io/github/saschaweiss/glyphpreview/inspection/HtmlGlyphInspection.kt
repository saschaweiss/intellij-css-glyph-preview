package io.github.saschaweiss.glyphpreview.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import io.github.saschaweiss.glyphpreview.detect.IconLibrary
import io.github.saschaweiss.glyphpreview.html.HtmlIconResolver

/**
 * Flags an icon-font class whose name is unknown, e.g. `<i class="fas fa-pencl">`
 * (typo for `fa-pencil`). Offers the closest registered name as a quick-fix.
 *
 * Conservative to avoid false positives:
 *  - only tokens that belong to a KNOWN icon library ([IconLibrary.fromHtmlClass]);
 *  - style/prefix tokens (`fas`, `fa-solid`, …) are skipped — they carry a weight,
 *    not an icon name;
 *  - only fires when that library's metadata map is actually registered (some
 *    sibling `fa-*` name is known). If the library isn't set up we stay silent,
 *    so users without a metadata map get zero noise.
 *
 * Residual edge: metadata-version skew (a valid FA7 name against a registered FA6
 * map) can mis-flag. Disable this inspection in Settings > Editor > Inspections if
 * that bites.
 */
class HtmlGlyphInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is XmlAttributeValue) return
                val attribute = element.parent as? XmlAttribute ?: return
                if (!attribute.name.equals("class", ignoreCase = true)) return
                inspect(element, holder)
            }
        }

    private fun inspect(value: XmlAttributeValue, holder: ProblemsHolder) {
        val known = HtmlIconResolver.nameToCodepoint()
        if (known.isEmpty()) return // no metadata registered → make no claims

        val text = value.value // class string without the surrounding quotes
        val valueStart = value.valueTextRange.startOffset

        for (match in CLASS_TOKEN.findAll(text)) {
            val token = match.value
            if (!token.contains('-')) continue                       // "fas", "btn", …
            if (HtmlIconResolver.weightFromClasses(token) != null) continue // style class, not a name
            IconLibrary.fromHtmlClass(token, null) ?: continue        // not a known-library icon class
            if (known.containsKey(token)) continue                    // recognised icon → fine

            // Only complain if THIS library's map is registered (a sibling is known),
            // so an un-set-up library never lights up.
            val libraryPrefix = token.substringBefore('-') + "-"
            if (known.keys.none { it.startsWith(libraryPrefix, ignoreCase = true) }) continue

            val suggestion = closestName(token, known.keys, libraryPrefix)
            val message = if (suggestion != null) {
                "Unknown icon \"$token\" — did you mean \"$suggestion\"?"
            } else {
                "Unknown icon \"$token\" — no such name in the registered map"
            }

            // Range is relative to the attribute-value element (which includes the quote).
            val tokenStartInElement = (valueStart - value.textRange.startOffset) + match.range.first
            val rangeInElement = TextRange(tokenStartInElement, tokenStartInElement + token.length)

            val fixes = if (suggestion != null) arrayOf<LocalQuickFix>(RenameIconFix(token, suggestion))
            else LocalQuickFix.EMPTY_ARRAY
            holder.registerProblem(
                holder.manager.createProblemDescriptor(
                    value, rangeInElement, message,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING, holder.isOnTheFly, *fixes,
                ),
            )
        }
    }

    /** Nearest known name of the same library within a small edit distance, or null. */
    private fun closestName(token: String, names: Set<String>, libraryPrefix: String): String? {
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        // Allow at most ~a third of the length in edits — enough for a typo, not a rename.
        val budget = maxOf(2, token.length / 3)
        for (name in names) {
            if (!name.startsWith(libraryPrefix, ignoreCase = true)) continue
            val d = levenshtein(token, name, budget)
            if (d in 1..budget && d < bestDistance) {
                bestDistance = d
                best = name
            }
        }
        return best
    }

    /** Bounded Levenshtein: bails out (returns > [max]) once the row minimum exceeds [max]. */
    private fun levenshtein(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > max) return max + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    private class RenameIconFix(private val from: String, private val to: String) : LocalQuickFix {

        override fun getFamilyName(): String = "Change to \"$to\""

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            val file = element.containingFile ?: return
            val pdm = PsiDocumentManager.getInstance(project)
            val doc = pdm.getDocument(file) ?: return

            // descriptor carries the token's range within the element.
            val rangeInElement = descriptor.textRangeInElement ?: return
            val start = element.textRange.startOffset + rangeInElement.startOffset
            val end = element.textRange.startOffset + rangeInElement.endOffset
            if (doc.getText(TextRange(start, end)) != from) return // stale — bail safely

            doc.replaceString(start, end, to)
            pdm.commitDocument(doc)
        }
    }

    companion object {
        private val CLASS_TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_-]*""")
    }
}
