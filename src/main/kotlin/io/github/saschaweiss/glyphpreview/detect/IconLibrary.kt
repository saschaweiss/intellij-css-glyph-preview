package io.github.saschaweiss.glyphpreview.detect

/**
 * A concrete icon usage the plugin found but couldn't render — the input to
 * auto-registration.
 *
 * [family] is known for CSS (`font-family` is in the code) but null for HTML
 * (`<i class="fas fa-pencil">` doesn't name the family/version).
 */
data class IconUsage(
    val libraryKey: String,
    val displayName: String,
    val family: String?,
    val weight: Int?,
    // Known for CSS (the `content` escape); lets detection pick the font that
    // actually contains the glyph. Not part of key() — dismissal is per font.
    val codepoint: Int? = null,
) {
    /**
     * Per-font key for "don't ask again" and session dedup:
     * `family|weight` when the family is known, else a coarser `library-weight`.
     */
    fun key(): String =
        if (family != null) "${family.lowercase()}|${weight ?: 0}"
        else "$libraryKey-${weight ?: 0}"
}

/** Registry of well-known icon fonts, used to recognise usages and locate the font on disk. */
object IconLibrary {

    private data class Lib(
        val key: String,
        val display: String,
        val classPrefixes: List<String>,
        val familyNeedles: List<String>, // lowercase substrings expected in the font-family / file
        val moduleDirs: List<String>,    // directory-name hints (node_modules, etc.)
    )

    private val LIBS = listOf(
        Lib("fontawesome", "Font Awesome", listOf("fa-"),
            listOf("font awesome", "fontawesome"), listOf("@fortawesome", "font-awesome", "fontawesome")),
        Lib("materialdesignicons", "Material Design Icons", listOf("mdi-"),
            listOf("material design icons", "materialdesignicons"), listOf("@mdi", "materialdesignicons", "mdi")),
        Lib("lineicons", "LineIcons", listOf("lni-"),
            listOf("lineicons"), listOf("lineicons")),
        Lib("linearicons", "Linearicons", listOf("lnr-"),
            listOf("linearicons"), listOf("linearicons")),
        Lib("unicons", "Unicons", listOf("uil-", "uis-", "uit-", "uir-"),
            listOf("unicons"), listOf("@iconscout", "unicons")),
        Lib("coreui", "CoreUI Icons", listOf("cil-", "cib-", "cif-"),
            listOf("coreui"), listOf("@coreui", "coreui")),
        Lib("remixicon", "Remix Icon", listOf("ri-"),
            listOf("remixicon", "remix icon"), listOf("remixicon")),
    )

    /**
     * From a CSS rule. Returns null unless the font-family is a RECOGNISED icon
     * library — so a plain `content: "\2022"` on a text font (proxima-nova, …)
     * never triggers an auto-registration prompt.
     */
    fun fromCss(family: String, weight: Int?, codepoint: Int? = null): IconUsage? {
        val lib = LIBS.firstOrNull { l -> l.familyNeedles.any { family.contains(it, ignoreCase = true) } }
            ?: return null
        return IconUsage(lib.key, lib.display, family, weight, codepoint)
    }

    /** From an HTML class token (`fa-pencil`, `mdi-account`); null if not a known icon class. */
    fun fromHtmlClass(token: String, weight: Int?): IconUsage? {
        val lib = LIBS.firstOrNull { l -> l.classPrefixes.any { token.startsWith(it) } } ?: return null
        return IconUsage(lib.key, lib.display, null, weight)
    }

    fun moduleDirsFor(libraryKey: String): List<String> =
        LIBS.firstOrNull { it.key == libraryKey }?.moduleDirs ?: emptyList()

    fun familyNeedlesFor(libraryKey: String): List<String> =
        LIBS.firstOrNull { it.key == libraryKey }?.familyNeedles ?: emptyList()
}
