# Third-party assets

## Material Symbols icons

The app icon set in `composeApp/src/commonMain/composeResources/drawable/` consists of
[Material Symbols](https://fonts.google.com/icons) glyphs in the **Rounded** style at weight 400.

- Name: Material Symbols (icon font converted to Android vector drawables)
- Copyright: Google LLC
- License: Apache License 2.0 — <https://www.apache.org/licenses/LICENSE-2.0>
- Upstream: <https://github.com/google/material-design-icons> (symbol fonts), mirrored for
  per-glyph SVG downloads at <https://github.com/marella/material-symbols>
  (`google/symbols/400/rounded/*.svg`).

Conversion: each glyph's SVG path was extracted from the upstream rounded SVG (24×24 viewport)
and written as an Android `<vector>` with a white fill so the app tints it through
`SwipeIcon`'s `ColorFilter`. No path geometry was modified.

Files (upstream name → `ic_<name>.xml`): `check`, `close`, `arrow_upward`, `undo`, `delete`,
`settings`, `chevron_right`, `arrow_back`, `cloud`, `favorite`, `motion_photos_on` (as `ic_live`),
`photo_library`, `shuffle`, `calendar_month`, `filter_list`.

The Apache-2.0 text of the license is reproduced upstream; the SPDX identifier for these assets is
`Apache-2.0`. Each XML file carries a short attribution header pointing here.
