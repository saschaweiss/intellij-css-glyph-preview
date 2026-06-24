# CSS Glyph Preview — JetBrains IDE Plugin

Preview icon-font glyphs (FontAwesome & co.) directly in the CSS/SCSS editor:

- **Gutter preview** next to every `content: "\fXXXX"` declaration — works from the `.ttf` alone.
- **Icon picker** (code completion) in the `content` value, searchable by icon name — and it inserts
  the required `font-weight` so the icon also renders in the browser.

Works in IDEs with bundled CSS support (IntelliJ IDEA Ultimate, WebStorm, PhpStorm,
PyCharm Professional, etc.). Not affiliated with or endorsed by Fonticons, Inc. (FontAwesome).

## Layout

```
build.gradle.kts            IntelliJ Platform Gradle Plugin 2.x, Kotlin, deps on com.intellij.css + sass
src/main/resources/META-INF/plugin.xml
src/main/kotlin/de/hostpress/glyphpreview/
  settings/GlyphSettings.kt        registered fonts (PersistentStateComponent)
  settings/GlyphConfigurable.kt    UI under Settings > Tools > CSS Glyph Preview
  settings/FontEntryDialog.kt      add/edit dialog + asset import/cleanup
  font/GlyphRenderer.kt            load font + render codepoint -> icon (cached)
  font/GlyphMetadata.kt            name<->codepoint from a CSS map (e.g. FA fontawesome.min.css)
  css/CssGlyphUtil.kt              content codepoint, font-family (incl. $SCSS vars) & font-weight
  marker/GlyphLineMarkerProvider.kt   gutter icon
  completion/GlyphCompletionContributor.kt  icon picker
```

## How font resolution works

1. The plugin reads `font-family` (resolving `$scss-variables`, project-wide) and `font-weight`
   from the rule block.
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
4. There: **Settings > Tools > CSS Glyph Preview > "+"** → pick a `.ttf` (e.g. `fa-solid-900.ttf`),
   set family `Font Awesome 7 Free`, weight `900`. For the picker, also point the metadata field at
   FontAwesome's `fontawesome.min.css`.
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
- SCSS variables are resolved per file **and** project-wide (cached, invalidated on change).
- Family matching by prefix: CSS `"Font Awesome 7 Free"` matches the registered `"… Free Solid"`
  via `font-weight`.

## Known limitations (intentionally left open)

- **woff2 is NOT supported** — `java.awt.Font` only reads `.ttf`/`.otf`. Register such a file
  (FontAwesome ships it in the desktop download / npm `webfonts` folder).
- **No full CSS cascade**: `font-family`/`font-weight` are read from the current block and its
  parent blocks, not resolved across arbitrary selectors (covers the common `&:after { … }` case).
- **SCSS variables** are resolved by text search, not via the Sass PSI — if the same variable is
  defined differently in multiple files, the first match wins (no `@use`/`@import` ordering).
- Adjust the platform version in `gradle.properties` (`2025.1`) to match your IDE if needed.

## Possible next steps

- Resolve SCSS variables via the real Sass PSI (instead of text search).
- Add hover documentation (`DocumentationProvider`) alongside the gutter.
- Bundle a FontAwesome name map so users only need to assign the font.

## License

MIT — see [LICENSE](LICENSE).
