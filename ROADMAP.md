# Roadmap

Planned work after 1.1.0. Sequenced so each step is independently testable, and so
the web-font decoders widen what auto-detection can offer.

Status: **1.2.0 shipped** — Phase A (auto-registration) ✅ and B1 (woff) ✅.
**B2 (woff2) is DEFERRED** (on the todo list) — see the notes under B2.

## Locked decisions

- **Registration stays application-level** (global), not per project. Auto-registered fonts
  appear everywhere; prefix family matching + `canDisplay` keep this unambiguous.
- **Notifications are reactive, not on project open.** They piggyback on the existing line-marker
  pass: when a resolver runs over an open CSS/SCSS/HTML file and finds an icon usage it cannot
  render (no matching font), it reports it — no separate file scan, no startup cost.
- **"Don't ask again" is per font**, keyed by `family | weight`
  (e.g. `Font Awesome 6 Pro | 900`, `Font Awesome 6 Brands | 400`), stored in a global ignore set.
  - CSS gives an exact key (`font-family` + `font-weight` are in the code).
  - HTML cannot name the family/version (`<i class="fas fa-pencil">` only implies solid/900). If the
    detector finds the font in the project, use the found font's exact identity; otherwise fall back
    to a coarser `library-style | weight` key (e.g. `fontawesome-solid | 900`).
- Auto-registration **never happens silently** — always one click to confirm.

## Phase A — Auto-registration (1.2.0) ✅ DONE

### A1 · `IconFontDetector`
Given a target library derived from an unresolved usage, search the project for a matching font +
CSS map. Targeted, not a blind `node_modules` walk:
- Known locations: `**/@fortawesome/*/webfonts`, `**/@mdi/font/fonts`, etc.
- Generic fallback: `.ttf`/`.otf`/`.woff`/`.woff2` files with a sibling `.css` that parses to
  >= N icon entries (reuse `GlyphMetadata` + `findMetadataNear` + filename→weight logic).
- Deduplicate against already-registered fonts.

### A2 · Library derivation from usage
- CSS: from the resolved `font-family` (`"Font Awesome 6 Pro"` → *fontawesome*) + `font-weight`.
- HTML: from the class prefix (`fa-`→fontawesome, `mdi-`→materialdesignicons, `lni-`→lineicons, …)
  + the style class weight (`fas`=900, `far`=400, `fab`=brands …).

### A3 · Reactive notification
Hook into the marker pass. When an icon usage is unresolved and the font's key is not in the global
ignore set, run the (targeted) detector and show ONE notification:
- **Found in project** → “Font Awesome detected (`node_modules/@fortawesome`) — register?”
  `[Register] [Don't ask again]`
- **Not found** → “You're using Font Awesome icons but no matching font is registered → Settings.”
  `[Open settings] [Don't ask again]`

`[Register]` reuses `FontAssets.importAsset` + builds a `FontEntry` + updates `GlyphSettings` +
clears caches. `[Don't ask again]` adds the font key to the global ignore set.

Open minor UX decision: when several fonts are needed in one file (e.g. Solid 900 + Brands 400),
show them as separate per-font prompts (rate-limited) vs one grouped prompt with per-font dismiss.

Risks: scan performance (→ targeted paths + capped generic scan) and false positives (→ only propose
pairs whose CSS map parses to >= N icons).

## Phase B — Web fonts (woff / woff2)

Shared idea: **decode once at registration**, cache the resulting `.ttf` in the config assets folder.
`GlyphRenderer` is untouched; no per-render cost. The file chooser also accepts `.woff`/`.woff2`.

### B1 · woff (1.2.0) ✅ DONE
`WoffDecoder`: read the woff header + table directory, zlib-inflate each table, reassemble the sfnt.
Wired into `FontAssets.importAsset` (decode on registration) and `GlyphRenderer.baseFont` (decode on
load, so family-name/`canDisplay` work on a raw `.woff`).

### B2 · woff2 — DEFERRED (todo)

Not started. There is no clean, low-risk path, which is why it's parked:

- **Brotli** decompression is easy: `org.brotli:dec` (Apache-2.0, **pure Java, no JNI**).
- The hard part is reversing the WOFF2 **glyf/loca transform** (encoders apply it by default):
  re-serialise glyph outlines and rebuild `loca` — several hundred lines, and bugs show up as
  "glyph looks wrong", not as clean errors. Cannot be verified without real fonts.
- Ready-made libraries evaluated and rejected:
  - **FontVerter** (`net.mabboud.fontverter`) does woff2→OpenType but pulls **jBrotli**, a
    **native JNI** dependency → bundling platform-specific binaries in a Marketplace plugin is a
    portability/review risk. Avoid.
  - **Aspose Font** is commercial/paid → incompatible with the MIT plugin. Out.

Recommendation when revisited: hand-roll with `org.brotli:dec` + a glyf-transform reversal (adapt
the WOFF2 spec / an MIT reference), behind the same `importAsset`/`GlyphRenderer` hooks as woff, with
real FA/MDI woff2 test fixtures. Low value vs. effort though — `.ttf`/`.otf`/`.woff` already cover
most cases and woff2→ttf is a trivial one-off conversion for users, so this stays low priority.

After B2, `IconFontDetector`'s `FONT_EXT` should add `woff2` so webfont-only libraries are offered.
