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
- Library license: MIT (see the upstream repository); the library itself adds no other assets.

The variable fonts ship inside the library's published artifacts; the standard Compose
Multiplatform resource pipeline embeds them in the app bundle. The app no longer carries any
icon files of its own (`composeResources/drawable/` was removed together with the hand-converted
vector drawables their path data had corrupted).

The Apache-2.0 text of the license is reproduced upstream; the SPDX identifier for these fonts is
`Apache-2.0`.
