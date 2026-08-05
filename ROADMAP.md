# Roadmap

Planned work after 1.1.0. Sequenced so each step is independently testable, and so
the web-font decoders widen what auto-detection can offer.

Milestones: **1.2.0** = auto-registration + woff, **1.3.0** = woff2.

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

## Phase A — Auto-registration (1.2.0)

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

### B1 · woff (1.2.0)
`WoffDecoder`: read the woff header + table directory, zlib-inflate each table, reassemble the sfnt.
~200 LOC, low risk. Test: decode a known `.woff` → `Font.createFont` succeeds → `canDisplay`.

### B2 · woff2 (1.3.0)
- Bundle a Brotli decoder (`org.brotli:dec`, Apache-2.0) as an `implementation` dependency — verify
  it lands in the plugin `lib/`.
- `Woff2Decoder`: parse the woff2 header, Brotli-decompress, then reverse the woff2 glyf/loca
  transform to reconstruct the sfnt (the hard, error-prone part — adapt an MIT/Apache reference).
- Mandatory test with real FontAwesome/MDI woff2 (decode → `Font.createFont` → `canDisplay`).
- Licensing: `org.brotli:dec` (Apache-2.0) is compatible with the MIT plugin; declare bundled deps
  in the marketplace listing if required.

After B2, `IconFontDetector` can also offer webfont-only libraries (many `node_modules` ship only
woff2).
