# CSS Glyph Preview — JetBrains IDE Plugin

Preview icon-font glyphs (FontAwesome & co.) directly in the editor — in CSS/SCSS **and** HTML.

**In CSS/SCSS:**

- **Gutter preview** next to every `content: "\fXXXX"` declaration — works from the `.ttf` alone.
- **Icon picker** (code completion) in the `content` value, searchable by icon name — and it inserts
  the required `font-weight` so the icon also renders in the browser.

**In HTML (and templates such as Twig):**

- **Gutter preview** next to `<i class="fas fa-pencil">`.
- FontAwesome **class completions show the icon inline** — the existing suggestions are decorated,
  not duplicated.

Works in IDEs with bundled CSS support (IntelliJ IDEA Ultimate, WebStorm, PhpStorm,
PyCharm Professional, etc.). Not affiliated with or endorsed by Fonticons, Inc. (FontAwesome).

## Layout

```
build.gradle.kts            IntelliJ Platform Gradle Plugin 2.x, Kotlin, deps on com.intellij.css + sass
src/main/resources/META-INF/plugin.xml
src/main/kotlin/io/github/saschaweiss/glyphpreview/
  settings/GlyphSettings.kt        registered fonts (PersistentStateComponent)
  settings/GlyphConfigurable.kt    UI under Settings > Tools > CSS Glyph Preview
  settings/FontEntryDialog.kt      add/edit dialog + asset import/cleanup
  font/GlyphRenderer.kt            load font + render codepoint -> icon (cached)
  font/GlyphMetadata.kt            name<->codepoint from a CSS map (e.g. FA fontawesome.min.css)
  css/CssGlyphUtil.kt              content codepoint, font-family (incl. $SCSS vars) & font-weight
  marker/GlyphLineMarkerProvider.kt          CSS gutter icon
  completion/GlyphCompletionContributor.kt   CSS icon picker
  html/HtmlIconResolver.kt                   class name/style -> codepoint/weight -> glyph (font-agnostic)
  html/HtmlGlyphLineMarkerProvider.kt        HTML gutter icon
  html/HtmlGlyphCompletionContributor.kt     decorates HTML class completions with the icon
  doc/GlyphDocumentationProvider.kt          hover / Quick Documentation preview (CSS + HTML)
```

## How font resolution works

1. The plugin reads `font-family` (resolving `$scss-variables` project-wide, including
   `@use` namespaces like `v.$font-awesome`) and `font-weight` from the rule block.
2. It looks up the registered fonts (Settings) whose family matches, preferring an exact
   `font-weight` match.
3. It renders the codepoint with the first matching font that actually contains the glyph —
   so a solid-only icon still previews even when the resolved weight points at a sparse regular font.

`font-weight` is the tiebreaker: 900 = solid, 400 = regular. Register FontAwesome Solid with
family `Font Awesome 7 Free` and weight `900`.

## Setup (development)

1. Open the `intellij-css-glyph-preview` folder in IntelliJ IDEA (with the Kotlin & Gradle plugins).
2. Sync Gradle (downloads the IDE dependencies — needs internet on first run).
3. Run the **`runIde`** task → a sandbox IDE boots with the plugin installed.
4. There: **Settings > Tools > CSS Glyph Preview > "+"** → pick a `.ttf`/`.otf` (e.g.
   `fa-solid-900.ttf`, or `materialdesignicons-webfont.ttf`). Family/weight are pre-filled from the
   file; the matching CSS name map (e.g. `fontawesome.min.css`, `materialdesignicons.min.css`) is
   auto-detected next to the font when possible, otherwise pick it via the metadata field.
5. Open an SCSS/CSS file → the gutter icon appears; in the `content` value run Basic Code Completion.

## Installing into your own IDE

1. `./gradlew buildPlugin` → `build/distributions/intellij-css-glyph-preview-<version>.zip`.
2. In your IDE: `Settings > Plugins > gear icon > Install Plugin from Disk…` → select the ZIP → restart.

## Features

- Gutter preview for `content: "\fXXXX"` (weight + font-family pick the font).
- Icon picker filtered to glyphs the font actually contains (`canDisplay`), searchable with and
  without the `fa-` prefix; inserting an icon sets the required `font-weight` automatically.
- Fonts and metadata are copied into the plugin config folder on selection — the originals may be
  deleted afterwards. Orphaned copies are cleaned up on save.
- Metadata parser understands FontAwesome 6/7 (`--fa:`) and the legacy `::before{content:}` form,
  including alias selectors.
- SCSS variables are resolved per file **and** project-wide, including `@use` namespaces
  (`v.$font-awesome`); cached and invalidated on change.
- Family matching by prefix: CSS `"Font Awesome 7 Free"` matches the registered `"… Free Solid"`
  via `font-weight`.
- **Font-agnostic**: any icon font with a codepoint CSS map works — FontAwesome, Material Design
  Icons, LineIcons, Linearicons, Unicons, CoreUI Icons, … The name map is auto-detected next to the
  font (the `.css` with the most icon entries) when adding it.
- **HTML/Twig**: gutter preview for `<i class="fas fa-pencil">` / `<i class="mdi mdi-account">`
  (each class token is tried against the registered maps), and class completions are decorated with
  the icon in place — no duplicate entries.
- **Hover / Quick Documentation** shows a larger glyph preview for CSS and HTML.

## Known limitations (intentionally left open)

- **woff2 is NOT supported** — `java.awt.Font` only reads `.ttf`/`.otf`. Register such a file
  (FontAwesome ships it in the desktop download / npm `webfonts` folder).
- **No full CSS cascade**: `font-family`/`font-weight` are read from the current block and its
  parent blocks, not resolved across arbitrary selectors (covers the common `&:after { … }` case).
- **SVG-sprite icon sets are not supported** — only real icon *fonts* (`.ttf`/`.otf` with an
  `@font-face` codepoint map) can be rendered. Sets shipped purely as SVG sprites have no glyph.
- **SCSS variables** are resolved by text search, not via the Sass PSI — `@use` namespaces are
  handled, but if the same variable is defined differently in multiple files, the first match wins.
- **Hover preview** currently triggers reliably in HTML; in CSS the Quick Documentation target
  isn't always picked up.
- Adjust the platform version in `gradle.properties` (`2025.1`) to match your IDE if needed.

## Possible next steps

- Full Sass PSI variable resolution (handles `!default` chains, maps; robust across imports).
- Make the CSS hover target trigger as reliably as the HTML one.

Done since 1.0.0: HTML/Twig support, font-agnostic icon sets with metadata auto-detection,
hover preview, `@use`-namespaced SCSS variables.

## License

MIT — see [LICENSE](LICENSE).
