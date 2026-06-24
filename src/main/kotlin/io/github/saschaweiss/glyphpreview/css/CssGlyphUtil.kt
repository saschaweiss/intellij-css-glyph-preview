package io.github.saschaweiss.glyphpreview.css

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssBlock
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * Text-driven helpers over the CSS/SCSS PSI.
 *
 * We deliberately lean on element text + regex rather than deep typed accessors:
 * it keeps the code resilient across CSS-plugin versions and works the same for
 * SCSS (which reuses the CSS PSI). Real cascade resolution is out of scope — we
 * read the `font-family`/`font-weight` declared in the SAME rule block, which
 * covers the overwhelmingly common `&:after { font-family: ...; content: ... }`.
 */
object CssGlyphUtil {

    private val CONTENT_CP = Regex("""content\s*:\s*["']\\([0-9A-Fa-f]+)""")
    private val FAMILY = Regex("""font-family\s*:\s*([^;}\r\n]+)""")
    private val WEIGHT = Regex("""font-weight\s*:\s*(\d{3})""")

    fun isContentDeclaration(text: String): Boolean = CONTENT_CP.containsMatchIn(text)

    /** Nearest enclosing CssDeclaration for a leaf element. */
    fun declarationOf(element: PsiElement): CssDeclaration? =
        PsiTreeUtil.getParentOfType(element, CssDeclaration::class.java, false)

    /** Nearest `{ ... }` block text, used to look up sibling declarations. */
    private fun blockText(element: PsiElement): String? =
        PsiTreeUtil.getParentOfType(element, CssBlock::class.java, false)?.text

    /** font-family declared in the same block, with quotes stripped and $vars resolved. */
    fun resolveFamily(element: PsiElement): String? {
        val block = blockText(element) ?: return null
        val raw = FAMILY.find(block)?.groupValues?.get(1)?.trim() ?: return null
        return normalizeFamily(raw, element)
    }

    /**
     * font-weight for the rule. Looks in the current block, then walks up parent
     * blocks (a light cascade for the `&:after` nested case), and defaults to 400
     * (normal) when none is set — matching how the browser resolves an
     * unspecified weight.
     */
    fun resolveWeight(element: PsiElement): Int {
        var block = PsiTreeUtil.getParentOfType(element, CssBlock::class.java, false)
        while (block != null) {
            WEIGHT.find(block.text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            block = PsiTreeUtil.getParentOfType(block, CssBlock::class.java, true)
        }
        return 400
    }

    private fun normalizeFamily(raw: String, element: PsiElement): String? {
        // Single value only — take the first family in a comma list.
        val first = raw.substringBefore(',').trim()
        if (first.startsWith("$")) {
            return resolveScssVariable(first.removePrefix("$"), element)
        }
        return first.trim('"', '\'', ' ').ifBlank { null }
    }

    /**
     * Best-effort SCSS variable lookup. Checks the current file first, then falls
     * back to scanning the project's .scss/.sass files for the `$name:` definition.
     *
     * Caveat: this is a plain text scan, not real Sass resolution — if the same
     * variable is defined differently in multiple files, the first match wins.
     */
    // Cross-file lookups are cached and invalidated whenever any PSI changes.
    private val crossFileVarCache = ConcurrentHashMap<String, String>()
    private var crossFileVarStamp = -1L

    private fun resolveScssVariable(name: String, element: PsiElement): String? {
        val regex = Regex("""\$${Regex.escape(name)}\s*:\s*([^;\r\n!]+)""")

        // 1. Same file (cheap, always fresh — never cached).
        element.containingFile?.text?.let { text ->
            regex.find(text)?.let { return clean(it.groupValues[1]) }
        }

        // 2. Project-wide .scss/.sass files, cached until the next PSI change.
        val project = element.project
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        if (stamp != crossFileVarStamp) {
            crossFileVarCache.clear()
            crossFileVarStamp = stamp
        }
        crossFileVarCache[name]?.let { return it }

        val scope = GlobalSearchScope.projectScope(project)
        for (ext in listOf("scss", "sass")) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: continue
                val value = regex.find(text)?.let { clean(it.groupValues[1]) }
                if (value != null) {
                    crossFileVarCache[name] = value
                    return value
                }
            }
        }
        return null
    }

    private fun clean(raw: String): String? =
        raw.trim().trim('"', '\'', ' ').ifBlank { null }
}
