package io.github.saschaweiss.glyphpreview.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * One registered icon font.
 *
 * [fontFamily] is matched (case-insensitively) against the `font-family` value
 * resolved from the CSS/SCSS rule. [weight] disambiguates families that are
 * registered multiple times (e.g. FontAwesome solid=900 vs regular=400);
 * 0 means "matches any weight".
 *
 * [metadataPath] is optional and only needed for the icon picker: it points at a
 * CSS file (like FontAwesome's `all.css`) that maps icon names to codepoints.
 * Without it the gutter still works; only the searchable completion is disabled.
 *
 * NOTE: fields are public `var` with defaults so IntelliJ's XML serializer can
 * persist them.
 */
class FontEntry {
    var displayName: String = ""
    var fontFamily: String = ""
    var fontFilePath: String = ""
    var weight: Int = 0
    var metadataPath: String? = null

    /**
     * CSS uses the base family ("Font Awesome 7 Free") and lets font-weight pick
     * the style, while the TTF's internal family is more specific
     * ("Font Awesome 7 Free Solid"). Accept exact OR base-prefix matches.
     */
    fun familyMatches(requested: String): Boolean {
        val req = requested.trim()
        if (req.isEmpty()) return false
        return fontFamily.equals(req, ignoreCase = true) ||
            fontFamily.startsWith(req, ignoreCase = true)
    }
}

@State(
    name = "io.github.saschaweiss.glyphpreview.GlyphSettings",
    storages = [Storage("cssGlyphPreview.xml")],
)
class GlyphSettings : PersistentStateComponent<GlyphSettings> {

    // Bound directly to the UI; mutated in place by the Configurable.
    var fonts: MutableList<FontEntry> = mutableListOf()

    override fun getState(): GlyphSettings = this

    override fun loadState(state: GlyphSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /**
     * All fonts whose family matches, ordered so an exact font-weight match comes
     * first. The caller picks the first one that can actually render the glyph —
     * this lets a solid-only icon still preview even when the resolved weight
     * points at a sparse regular font.
     */
    fun candidates(family: String?, weight: Int?): List<FontEntry> {
        val req = family?.trim().orEmpty()
        if (req.isEmpty()) return emptyList()
        return fonts.filter { it.familyMatches(req) }
            .sortedByDescending { weight != null && it.weight == weight }
    }

    companion object {
        fun getInstance(): GlyphSettings =
            ApplicationManager.getApplication().getService(GlyphSettings::class.java)
    }
}
