package io.github.saschaweiss.glyphpreview.detect

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import io.github.saschaweiss.glyphpreview.font.GlyphRenderer
import io.github.saschaweiss.glyphpreview.settings.FontAssets

/**
 * Searches the project for a font file (+ CSS name map) that satisfies a given
 * [IconUsage], so auto-registration can offer it with one click.
 *
 * Must run inside a read action (uses the VFS). Bounded by [MAX_VISITS] to stay
 * cheap on large projects.
 */
object IconFontDetector {

    data class Match(val fontPath: String, val family: String, val weight: Int, val metadataPath: String?)

    private val FONT_EXT = setOf("ttf", "otf", "woff") // woff decoded on registration; woff2 later
    private val SKIP_DIRS = setOf(".git", ".idea", "dist", "build", "target", "vendor", ".cache", "cache")
    private const val MAX_VISITS = 60_000

    fun detect(project: Project, usage: IconUsage): Match? {
        val needles = IconLibrary.familyNeedlesFor(usage.libraryKey)
        val moduleDirs = IconLibrary.moduleDirsFor(usage.libraryKey)

        val matching = collectFontFiles(project).filter { belongsToLibrary(it, needles, moduleDirs) }
        if (matching.isEmpty()) return null

        // Prefer a font that actually contains the glyph (when the codepoint is known,
        // i.e. CSS), then an exact weight match, then a canonical npm-module location.
        // This stops e.g. fa-brands-400 from being chosen over fa-regular-400 for an
        // icon that only exists in the regular font.
        val chosen = matching.sortedWith(
            compareByDescending<VirtualFile> {
                usage.codepoint != null && GlyphRenderer.canDisplay(it.path, usage.codepoint)
            }
                .thenByDescending { usage.weight != null && filenameWeight(it) == usage.weight }
                .thenByDescending { file -> moduleDirs.any { file.path.contains(it, ignoreCase = true) } },
        ).first()

        val path = chosen.path
        val family = usage.family ?: GlyphRenderer.familyNameOf(path) ?: baseName(chosen)
        val weight = usage.weight ?: filenameWeight(chosen) ?: 0
        return Match(path, family, weight, FontAssets.findMetadataNear(path))
    }

    private fun collectFontFiles(project: Project): List<VirtualFile> {
        val out = ArrayList<VirtualFile>()
        var visits = 0
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any?>() {
                override fun visitFileEx(file: VirtualFile): Result {
                    ProgressManager.checkCanceled() // stay cancellable → yield to write actions
                    if (visits++ > MAX_VISITS) return SKIP_CHILDREN
                    if (file.isDirectory) {
                        return if (file.name in SKIP_DIRS) SKIP_CHILDREN else CONTINUE
                    }
                    if (file.extension?.lowercase() in FONT_EXT) out.add(file)
                    return CONTINUE
                }
            })
        }
        return out
    }

    private fun belongsToLibrary(file: VirtualFile, needles: List<String>, moduleDirs: List<String>): Boolean {
        val path = file.path.lowercase()
        val name = file.name.lowercase()
        if (moduleDirs.any { path.contains(it.lowercase()) }) return true
        if (needles.any { name.contains(it) || path.contains(it) }) return true
        // Last resort: read the font's own family name (cached by GlyphRenderer).
        val family = GlyphRenderer.familyNameOf(file.path)?.lowercase()
        return family != null && needles.any { family.contains(it) }
    }

    private fun baseName(file: VirtualFile): String = file.name.substringBeforeLast('.')

    private fun filenameWeight(file: VirtualFile): Int? =
        Regex("""(\d{3})""").find(baseName(file))?.groupValues?.get(1)?.toIntOrNull()
}
