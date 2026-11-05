# Third-party assets

## Material Symbols icons

All app icons render at runtime through
[`dev.vicart:compose-material-symbols`](https://github.com/ClementVicart/compose-material-symbols)
(version pinned in `gradle/libs.versions.toml`), a Compose Multiplatform library that draws
Google's [Material Symbols](https://fonts.google.com/icons) from their variable fonts. The app
uses the **Rounded** style at weight 400 (REQUIREMENTS §5.5) via
`dev.swipe.ui.components.SwipeIcon` / `SwipeIcons`.

- Name: Material Symbols (icon fonts, bundled in the library binaries)
- Copyright: Google LLC
- License: Apache License 2.0 — <https://www.apache.org/licenses/LICENSE-2.0>
- Upstream: <https://github.com/google/material-design-icons>
- Library license: Apache License 2.0 (declared in the published POM of
  `dev.vicart:compose-material-symbols`); the library itself adds no other assets.

The variable fonts ship inside the library's published artifacts; the standard Compose
Multiplatform resource pipeline embeds them in the app bundle. The app no longer carries any
icon files of its own (`composeResources/drawable/` was removed together with the hand-converted
vector drawables their path data had corrupted).

**Bundle-size tradeoff:** the library's single artifact bundles all three symbol styles
(outlined ~10.5 MB, rounded ~15 MB, sharp ~8.8 MB); the app uses only Rounded, but the Compose
Resources pipeline embeds a dependency's full resource set with no consumer-side exclusion, so
the Debug `.app` grows ≈ +34 MB (63 → 96 MB, measured 2026-11-06). Accepted for v1; the lever
if it ever matters is font subsetting upstream or a rounded-only fork.

The Apache-2.0 text of the license is reproduced upstream; the SPDX identifier for these fonts is
`Apache-2.0`.
