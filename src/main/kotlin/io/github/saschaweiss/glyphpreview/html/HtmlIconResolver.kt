package io.github.saschaweiss.glyphpreview.html

import com.intellij.util.ui.JBUI
import io.github.saschaweiss.glyphpreview.font.GlyphMetadata
import io.github.saschaweiss.glyphpreview.font.GlyphRenderer
import io.github.saschaweiss.glyphpreview.settings.GlyphSettings
import javax.swing.Icon

/**
 * Resolves icon-font usage in HTML (`<i class="fas fa-pencil">`, `<i class="mdi mdi-account">`, …)
 * to a rendered glyph — the HTML counterpart to the CSS side.
 *
 * Font-agnostic: it does not assume FontAwesome. Every class token in the
 * attribute is tried against the registered metadata maps; whichever maps to a
 * codepoint is the icon (utility/style tokens like `fas`, `mdi`, `lni` simply
 * have no codepoint and are skipped). Works for Material Design Icons,
 * LineIcons, Linearicons, Unicons, CoreUI Icons, etc. as long as their font +
 * CSS map are registered in the settings.
 *
 * Read-only over the registry/metadata/renderer.
 */
object HtmlIconResolver {

    /**
     * Known style classes -> font-weight. This is mostly FontAwesome, which has
     * multiple weights (solid/regular/light). Single-weight fonts (MDI, LineIcons…)
     * don't need an entry here — the correct font is found via canDisplay anyway.
     */
    private val STYLE_WEIGHT = mapOf(
        "fas" to 900, "fa-solid" to 900,
        "far" to 400, "fa-regular" to 400,
        "fal" to 300, "fa-light" to 300,
        "fat" to 100, "fa-thin" to 100,
        "fad" to 900, "fa-duotone" to 900,
        "fab" to 400, "fa-brands" to 400,
    )

    /** Any CSS class token. */
    private val CLASS_TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_-]*""")

    /** Combined icon-name -> codepoint map from all registered metadata maps. */
    fun nameToCodepoint(): Map<String, Int> {
        val map = HashMap<String, Int>()
        for (entry in GlyphSettings.getInstance().fonts) {
            val meta = entry.metadataPath ?: continue
            for (glyph in GlyphMetadata.load(meta)) map.putIfAbsent(glyph.name, glyph.codepoint)
        }
        return map
    }

    /** Weight implied by a style class in the attribute, or null if none is known. */
    fun weightFromClasses(classes: String): Int? {
        for (token in classes.trim().split(Regex("\\s+"))) {
            STYLE_WEIGHT[token]?.let { return it }
        }
        return null
    }

    /** Render a codepoint with the weight-preferred registered font that contains it. */
    fun renderCodepoint(codepoint: Int, weight: Int?, size: Int = JBUI.scale(13)): Icon? {
        val fonts = GlyphSettings.getInstance().fonts
        val ordered = if (weight == null) fonts else fonts.sortedByDescending { it.weight == weight }
        return ordered.firstNotNullOfOrNull { GlyphRenderer.icon(it.fontFilePath, codepoint, size) }
    }

    /** First class token in [classes] that maps to a registered icon: (name, codepoint). */
    fun firstCodepoint(classes: String): Pair<String, Int>? {
        val names = nameToCodepoint()
        if (names.isEmpty()) return null
        for (match in CLASS_TOKEN.findAll(classes)) {
            names[match.value]?.let { return match.value to it }
        }
        return null
    }

    /** First class token in [classes] that maps to a registered icon, with its rendered glyph. */
    fun firstRenderable(classes: String): Pair<String, Icon>? {
        val (name, codepoint) = firstCodepoint(classes) ?: return null
        val icon = renderCodepoint(codepoint, weightFromClasses(classes)) ?: return null
        return name to icon
    }
}
