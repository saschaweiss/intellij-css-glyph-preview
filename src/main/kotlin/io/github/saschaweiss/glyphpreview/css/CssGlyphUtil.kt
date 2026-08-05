package io.github.saschaweiss.glyphpreview.css

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
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

    // Type-agnostic climb over the PSI parent chain. SCSS nesting doesn't always
    // expose the outer container as a CssBlock, so walking CssBlock-only misses a
    // font-family/weight declared in a sibling rule (e.g. `&:before, &:after {…}`
    // next to a separate `&:after { content }`). Walking generic parents and
    // scanning each ancestor's text finds it. Capped to avoid runaway/over-reach.
    private const val MAX_CLIMB = 12

    private fun ancestors(element: PsiElement): Sequence<PsiElement> = sequence {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && current !is PsiFile && depth < MAX_CLIMB) {
            yield(current)
            current = current.parent
            depth++
        }
    }

    /** The pseudo-element (`before`/`after`) the content belongs to, or null. */
    private fun pseudoOf(element: PsiElement): String? {
        val selector = PsiTreeUtil.getParentOfType(element, CssRuleset::class.java)
            ?.text?.substringBefore('{') ?: return null
        return when {
            Regex("""::?after\b""").containsMatchIn(selector) -> "after"
            Regex("""::?before\b""").containsMatchIn(selector) -> "before"
            else -> null
        }
    }

    // Matches a `font-family`/`font-weight` declared inside a rule that targets the
    // given pseudo-element — so the element's OWN font (e.g. proxima-nova on a button)
    // is ignored, and only `::before`/`::after` rules count. `[^{}]` keeps each match
    // inside a single block.
    private fun pseudoFamilyRegex(p: String) =
        Regex("""::?$p\b[^{}]*\{[^{}]*?font-family\s*:\s*([^;}\r\n]+)""")

    private fun pseudoWeightRegex(p: String) =
        Regex("""::?$p\b[^{}]*\{[^{}]*?font-weight\s*:\s*(\d{3})""")

    /**
     * font-family for the rule. For a `::before`/`::after` `content`, only rules
     * targeting that same pseudo count (climbing ancestors) — this catches the
     * FontAwesome pattern where `font-family` lives in a sibling `&:before, &:after`
     * rule while `content` sits in a separate `&:after`, and ignores the element's
     * own text font. SCSS variables (incl. @use namespaces) are resolved.
     */
    fun resolveFamily(element: PsiElement): String? {
        val pseudo = pseudoOf(element)
        val regex = if (pseudo != null) pseudoFamilyRegex(pseudo) else FAMILY
        for (ancestor in ancestors(element)) {
            regex.find(ancestor.text)?.let { return normalizeFamily(it.groupValues[1].trim(), element) }
        }
        return null
    }

    /**
     * font-weight for the rule (pseudo-aware, same as [resolveFamily]); defaults to
     * 400 (normal) when none is found — matching how the browser resolves an
     * unspecified weight.
     */
    fun resolveWeight(element: PsiElement): Int {
        val pseudo = pseudoOf(element)
        val regex = if (pseudo != null) pseudoWeightRegex(pseudo) else WEIGHT
        for (ancestor in ancestors(element)) {
            regex.find(ancestor.text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return 400
    }

    // A SCSS variable usage, with optional @use namespace: `$name` or `ns.$name`.
    private val SCSS_VAR_USAGE = Regex("""^(?:[A-Za-z_][\w-]*\.)?\$([A-Za-z0-9_-]+)$""")

    private fun normalizeFamily(raw: String, element: PsiElement): String? {
        // Single value only — take the first family in a comma list.
        val first = raw.substringBefore(',').trim()
        SCSS_VAR_USAGE.find(first)?.let { match ->
            // Handles both `$font-awesome` and `@use`-namespaced `v.$font-awesome`.
            return resolveScssVariable(match.groupValues[1], element)
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

        val value = runCatching { scanContentRoots(project, regex) }.getOrNull()
        if (value != null) crossFileVarCache[name] = value
        return value
    }

    // Directory names not worth walking when searching for a variable definition.
    private val SKIP_DIRS = setOf("node_modules", "vendor", ".git", ".idea", "dist", "build", "target")

    /**
     * Walks the project's content roots via the VFS looking for the `$name:`
     * definition. Deliberately does NOT use the filename index / search scopes —
     * those miss files in some setups (e.g. symlinked ServBay/WordPress projects).
     */
    private fun scanContentRoots(project: Project, regex: Regex): String? {
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            var found: String? = null
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any?>() {
                override fun visitFileEx(file: VirtualFile): Result {
                    if (found != null) return SKIP_CHILDREN
                    if (file.isDirectory) {
                        return if (file.name in SKIP_DIRS) SKIP_CHILDREN
                        else CONTINUE
                    }
                    val ext = file.extension?.lowercase()
                    if (ext == "scss" || ext == "sass") {
                        runCatching { VfsUtilCore.loadText(file) }.getOrNull()?.let { text ->
                            regex.find(text)?.let { found = clean(it.groupValues[1]) }
                        }
                    }
                    return CONTINUE
                }
            })
            if (found != null) return found
        }
        return null
    }

    private fun clean(raw: String): String? =
        raw.trim().trim('"', '\'', ' ').ifBlank { null }
}
