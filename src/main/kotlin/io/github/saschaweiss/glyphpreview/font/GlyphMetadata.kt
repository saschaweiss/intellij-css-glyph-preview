package io.github.saschaweiss.glyphpreview.font

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One pickable icon: a human name plus its codepoint. */
data class GlyphInfo(val name: String, val codepoint: Int)

/**
 * The icon *names* are NOT in the font binary — they live in the icon set's CSS.
 * We parse a CSS map (e.g. FontAwesome's `all.css`) for rules of the form:
 *
 *     .fa-house::before { content: "\f015"; }
 *
 * That gives us name -> codepoint, which powers the searchable picker.
 * Plain icon fonts without such a map still get the gutter preview; they just
 * won't have a searchable completion list.
 */
object GlyphMetadata {

    // The selector list is captured as group 1 (so alias selectors like
    // ".fa-home,.fa-house{...}" yield BOTH names), the codepoint as group 2.

    // FontAwesome 6/7: name -> codepoint via a CSS custom property,
    //   .fa-house{--fa:"\f015"}
    private val VAR_RULE = Regex(
        """([^{}]+?)\{[^}]*?--fa\s*:\s*["']\\([0-9A-Fa-f]+)["']""",
        RegexOption.DOT_MATCHES_ALL,
    )

    // Older FontAwesome (and many other icon fonts):
    //   .fa-trash::before { content: "\f1f8" }
    private val BEFORE_RULE = Regex(
        """([^{}]+?)::?before\s*\{[^}]*?content\s*:\s*["']\\([0-9A-Fa-f]+)["']""",
        RegexOption.DOT_MATCHES_ALL,
    )

    // Class names within a selector list.
    private val CLASS_NAME = Regex("""\.([A-Za-z0-9_-]+)""")

    private val cache = ConcurrentHashMap<String, List<GlyphInfo>>()

    fun load(cssPath: String): List<GlyphInfo> = cache.getOrPut(cssPath) {
        val file = File(cssPath)
        if (!file.isFile) return@getOrPut emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return@getOrPut emptyList()

        val found = LinkedHashMap<String, GlyphInfo>()
        for (rule in listOf(VAR_RULE, BEFORE_RULE)) {
            for (m in rule.findAll(text)) {
                val cp = m.groupValues[2].toIntOrNull(16) ?: continue
                for (cls in CLASS_NAME.findAll(m.groupValues[1])) {
                    val name = cls.groupValues[1]
                    found.putIfAbsent(name, GlyphInfo(name, cp))
                }
            }
        }
        found.values.sortedBy { it.name }
    }

    fun clearCache() = cache.clear()
}
