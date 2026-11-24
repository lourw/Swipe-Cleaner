# Swipe Cleaner — Requirements, Design & Architecture

_Last updated: 2026-11-09 · Status: approved for implementation_

Swipe Cleaner is a personal iPhone app, built with **Kotlin Multiplatform + Compose Multiplatform**. It cleans up the photo library one item at a time, using a card-swipe interface:

- **Swipe right → Keep.** The item is remembered and never shown again.
- **Swipe left → Delete.** The item goes into a _To Delete_ queue. Nothing is deleted until you review the queue and confirm the whole batch.
- **Swipe up → Skip.** No decision is made, and the item can come back in a later session.

Deleted items go to iOS **Recently Deleted**, where they stay recoverable for 30 days.

---

## 1. Goals and non-goals

**Goals**

1. Review at least 30 items per minute, with no visible loading between cards.
2. Never delete without an explicit batch confirmation, both in the app and in the iOS system dialog.
3. Let a session start from the whole library (shuffled), an album, a month, or a smart filter.
4. Remember every decision, so a kept item never shows up again.
5. Keep the code structured so an Android build is mostly a new platform module.

**Non-goals for v1**

- No in-app accounts, cloud sync, or analytics.
- No editing, favoriting, or album management. (Sharing the current item through the iOS share sheet was added on 2026-11-09, FR-32.)
- No ML-based duplicate or blur detection. Smart filters use metadata only.

## 2. Decisions

| Topic | Decision | Consequence |
| --- | --- | --- |
| UI stack | Compose Multiplatform shared UI | All screens are written in Kotlin. Video playback uses a UIKit interop view. PhotoKit is called from Kotlin/Native (`platform.Photos`). |
| Deletion | Batch delete at the end | Left swipes go into a persisted queue. A single `PHAssetChangeRequest.deleteAssets` call means a single iOS confirmation dialog. |
| Deck scope | Shuffle, album or month, smart filters | The default deck is the whole library, shuffled. Picking a source or a filter narrows it. |
| Install | TestFlight with a paid Apple Developer account, or a free Apple ID through Xcode | TestFlight builds last 90 days. Free-provisioned builds must be re-signed every 7 days and can't use TestFlight. No paid entitlements are needed. |
| Min OS | iOS 17.0 | Uses modern PhotoKit, including limited-library APIs. |
| Platforms | iOS only for v1 | `androidMain` exists as a stub so the structure stays honest. |

**Open questions (the default in brackets is what we build unless someone changes it)**

All three were settled during the WP-6.4 release sweep (2026-11-05) against what shipped:

- [x] Long-press on a Live Photo plays its motion? **[Yes]** — Resolved: implemented, FR-17 (hold plays the paired video until release, `DeckMotion`).
- [x] Include iCloud-only originals that aren't on the device? **[Yes. Allow network access, and show a cloud badge while the item loads.]** — Resolved: implemented, the Settings "Include iCloud-only items" toggle (FR-29) with the cloud badge while an item downloads.
- [x] Show an estimate of storage freed? **[Only if the file size can be read reliably. See Risk R3.]** — Resolved as deferred: no reliable API covers what R3 demands (PHAsset size doesn't track Recently Deleted), so v1 ships no estimate. Revisit post-v1.

## 3. Functional requirements

IDs are stable. Reference them in commits and tests, for example `FR-12`.

### Permissions
- **FR-1** On first launch, open Home and request `PHAccessLevel.readWrite` automatically; the iOS prompt appears over Home's placeholder state. A user who has already answered the prompt is never asked again.
- **FR-2** If access is **limited**, show a persistent banner: "You've given access to N items. Manage." Tapping it opens `presentLimitedLibraryPicker`.
- **FR-3** If access is **denied**, show a blocking screen with an "Open Settings" button that opens `UIApplication.openSettingsURLString`.

### Session setup (Home)
- **FR-4** Home lists the session sources: **Shuffle everything**, **Albums**, **By month**, and **Smart filters**.
- **FR-5** The media type toggle offers **All / Photos / Videos**, applied on top of any source.
- **FR-6** Smart filters for v1:
  - Screenshots (`PHAssetMediaSubtype.photoScreenshot`)
  - Long videos (duration ≥ 60 s, longest first)
  - Bursts (assets that have a `burstIdentifier`)
  - Likely duplicates: items taken within 2 s of each other with identical pixel dimensions, shown next to each other
  - Selfies (the smart album `smartAlbumSelfPortraits`)
- **FR-7** **By month** lists year → month buckets with item counts, newest first.
- **FR-8** **Albums** lists user albums and the main smart albums, with cover thumbnails and counts.
- **FR-9** Every source excludes items that already have a **Keep** or a pending **Delete** decision.
- **FR-10** Home shows lifetime stats (reviewed, kept, deleted) and the size of the current To Delete queue, with a button to review it.
- **FR-37** Every row on the Albums screen carries an **Exclude from Shuffle** switch, off by default. Items in any excluded album leave the **Shuffle everything** deck — even items that are also in a non-excluded album. Nothing else is affected: opening an excluded album, By month, and the smart filters still include its items, and the album's own row keeps its full count. Exclusions persist locally as album ids only; an album the user has since deleted is ignored. The Shuffle count on Home always equals the deck its row opens (FR-9) and refreshes when an exclusion changes. Toggling mid-session affects the next deck or rebuild only — never the card on screen. Exclusion never removes anything from the deletion path: a Shuffle item deleted still leaves the library and every album.

### Swiping
- **FR-11** The deck shows one item full-bleed, with the next item rendered underneath it.
- **FR-12** Right swipe or the Keep button records `KEEP` and advances to the next item.
- **FR-13** Left swipe or the Delete button records `DELETE` (queued, not yet deleted) and advances.
- **FR-14** Up swipe or the Skip button records `SKIP` for this session only. The item can appear in later sessions.
- **FR-15** **Undo** reverts the last decision and brings the card back. The undo stack holds up to 50 steps per session. The stack is in-memory only: after process death the resumed session starts with an empty undo stack. While a decision write is in flight, an Undo tap is ignored rather than queued: the deck never rewinds under a completing write, so a card whose verdict is being stored is never re-shown.
- **FR-16** Videos autoplay muted and loop. Tapping the video toggles sound, and the mute state is remembered for the session. A scrubber shows progress.
- **FR-17** Long-press on a Live Photo plays its motion.
- **FR-18** The card shows metadata: date, and the duration for videos. A badge marks Live Photos, iCloud items, and favorites.
- **FR-19** The progress indicator shows `reviewed / total` for the session.
- **FR-20** Every decision is saved immediately, so killing the app loses nothing.
- **FR-21** When the deck runs out, show a session summary: kept, queued for deletion, skipped, and a CTA to review the queue. A deck that **opens** empty never runs out: the screen shows its §5.8 empty state instead of a summary, and the session row ends immediately — with no decisions and nothing to resume, the session is over as soon as it opened (WP-7.3).
- **FR-22** A favorited item (`PHAsset.isFavorite`) swiped left is asked about before it is queued: a compact confirm card anchored at the bottom of the deck stage above the action bar (§5.7 `FavoriteConfirmCard`), titled "Delete this favorite?" with a supporting line, and **Cancel** and **Delete** actions. The card never covers or resizes the photo, stays until the user acts or the deck moves on, and nothing is pre-selected or auto-accepted (NFR-5): the DELETE is recorded only when **Delete** is tapped. Starting a drag on the pending card while the card is up cancels the confirmation (same as **Cancel**) and records nothing; the gesture that follows is a fresh verdict — right/keep commits normally, and a completed left/delete swipe re-raises this confirmation rather than queueing, since a favorite's DELETE is written only on **Delete** (U3).
- **FR-32** A **Share** button on the swipe screen opens the iOS share sheet (`UIActivityViewController`) for the current item. It shares the original media: the photo, the video, or a Live Photo's photo plus its paired video, never a still frame of a video or Live Photo (an edited item shares its current rendition, as Photos does). The media loads only when the button is tapped, and the temp files are deleted once the sheet finishes. While it loads (for example an iCloud download, allowed per the FR-29 "Include iCloud-only items" setting) the button shows a loading state and ignores further taps; if it can't load, a toast says "Couldn't load this item to share — try again". Opening, completing, or cancelling the share sheet never records a decision, advances the deck, or changes the undo stack; swiping away while the media is still loading abandons the share. This replaces opening the item in the Photos app, which iOS has no documented way to do for one specific asset.
- **FR-38** A **Hide** button on the swipe screen hides the current item in the Photos library (the asset's `hidden` flag: it leaves the library grid and stays in the Hidden album). Hiding is **immediate** — no in-app confirmation and no queue; Undo is the safety net. It is not a decision: no decision row is written, the item counts toward none of the FR-21 kept / queued / skipped numbers, and the deck's kept/skip state is untouched — but it does advance the deck and the FR-19 progress like a decision would. The deck advances only after the hide write succeeds (NFR-7); on success a 3 s toast reads "Hidden in Photos", on failure the card stays and a toast reads "Couldn't hide this item — try again". The button is disabled while a hide or a decision write is in flight. **Undo** (FR-15) unhides exactly the asset the undo entry names and brings the card back in place (§5.6). Hiding a favorite whose FR-22 confirm is pending clears the confirm; Undo restores it with the returned card.

### Review and delete
- **FR-23** The Review Queue shows queued items in a 3-column grid, newest decision first.
- **FR-24** Tapping a tile opens it full-screen. That view has a **Restore** action, which removes it from the queue and records `KEEP`.
- **FR-25** "Delete N items" calls one `PHPhotoLibrary.performChanges { deleteAssets }` for the whole queue, which triggers the iOS system dialog.
- **FR-26** If the user confirms in iOS, the items are marked `DELETED` in the database and removed from the queue, and a success screen appears. Only ids the library confirmed gone (deleted now, or already gone) count: an id PhotoKit could not resolve — a burst frame, or an asset outside a Limited selection — stays queued, and the success screen reports it ("N deleted, M couldn't be found").
- **FR-27** If the user cancels the iOS dialog, the queue stays as it was and no error is shown.
- **FR-28** Queued items whose assets no longer exist (deleted elsewhere) are pruned silently whenever the queue is loaded. The prune only ever removes pending-`DELETE` rows — a `KEEP` decision is never erased (WP-7.6).

### Settings
- **FR-29** Settings toggles: haptics, include iCloud-only items, autoplay videos.
- **FR-30** "Reset all decisions" clears the KEEP history after a confirmation. It does not touch the delete queue.
- **FR-31** Settings shows the app version and build, and the date the current signing expires if it can be determined (otherwise this is skipped).

## 4. Non-functional requirements

| ID | Area | Requirement |
| --- | --- | --- |
| NFR-1 | Speed | Next card is fully visible (at least the degraded image) within 100 ms of a swipe. The full-quality image follows within 500 ms for local assets. |
| NFR-2 | Smoothness | Card drag runs at the display refresh rate (120 Hz on ProMotion) with no dropped frames from image decoding. |
| NFR-3 | Memory | Under 300 MB resident. At most 5 decoded full-screen bitmaps in memory at once. |
| NFR-4 | Startup | Cold start to an interactive Home in under 1.5 s with a library of 50k items. Use lazy fetches; never load the whole library into memory. |
| NFR-5 | Safety | The only code path that deletes assets is `CommitDeletionsUseCase`, and it requires the in-app confirmation: the use case only accepts an `InAppDeleteConfirmation` token that only the confirm dialog's Delete tap can construct (§6.7). Since WP-9.1 the single-path rule is structurally guaranteed: `delete` lives on `MediaDeleter`, not on `MediaLibrary` (compile-time), and the graph binds it only into the use case, guarded by a DI smoke test and check-rules (§6.4, §6.7). |
| NFR-6 | Privacy | No network calls except PhotoKit's iCloud downloads. No analytics. All data stays on the device. |
| NFR-7 | Durability | Decisions are written to SQLite before the UI advances. If that write fails, the swipe is reverted. |
| NFR-8 | Accessibility | Every swipe action is also available as a button. VoiceOver labels on all controls. The swipe card also exposes Keep, Delete, Skip, and Hide (and Undo when an undo is pending) as VoiceOver custom actions, and describes itself with its capture date, media type, duration, and favorite flag. Honours Reduce Motion (see §5.6). Dynamic Type is out of scope for v1: the layout targets the system default text size. |

---

## 5. UI specification and design language

### 5.1 Principles

1. **The photo is the interface.** Chrome is minimal, translucent, and stays at the edges.
2. **One decision at a time.** Every screen has one obvious primary action.
3. **Color means verdict.** Green means keep, red means delete, and grey means skip. These colors are never used for anything else.
4. **Reversible by default.** Undo is always one tap away, and nothing destructive happens without confirmation.
5. **Safe areas are content areas.** Interactive and readable content never sits under the status bar or the Home Indicator; backgrounds and the swipe-card image paint edge-to-edge behind both.

### 5.2 Theme

v1 is **dark only**, because photos look best on black. All values are defined once in `ui/theme/` and referenced only through the `SwipeTheme` tokens. Hardcoded colors or sizes in screens are not allowed.

### 5.3 Color tokens

| Token | Hex | Use |
| --- | --- | --- |
| `bg` | `#000000` | App background, behind cards |
| `surface` | `#121214` | Sheets, Home list rows |
| `surfaceRaised` | `#1C1C1F` | Buttons on surface, tiles |
| `border` | `#2C2C30` | Hairlines, 1 px |
| `scrim` | `#000000` @ 55% | Gradient behind card metadata and top bar |
| `textPrimary` | `#F5F5F7` | Titles, body |
| `textSecondary` | `#A1A1A6` | Metadata, captions |
| `textTertiary` | `#6E6E73` | Disabled, placeholders |
| `accent` | `#0A84FF` | Links and neutral primary actions (Start session) |
| `keep` | `#30D158` | Keep stamp, Keep button, keep counts |
| `delete` | `#FF453A` | Delete stamp, Delete button, queue badge, destructive CTA |
| `skip` | `#8E8E93` | Skip stamp, Skip button |
| `warning` | `#FFD60A` | Favorite-delete toast, limited-access banner |

Tinted fills use the verdict color at 16% alpha, for example the Keep button background `keep @ 16%` with a `keep` icon.

### 5.4 Typography

Use `FontFamily.Default`, which should render as SF Pro on iOS. Confirm this in M1. If it doesn't, bundle Inter and use that instead.

> WP-1.2 note: components use `FontFamily.Default` only — no font asset is bundled. The SF Pro rendering is pending visual confirmation on device/simulator (👤 M1 checkpoint).

| Token | Size / line (sp) | Weight | Use |
| --- | --- | --- | --- |
| `display` | 34 / 41 | Bold | Summary numbers, Home title |
| `title` | 22 / 28 | Semibold | Screen titles |
| `headline` | 17 / 22 | Semibold | Row titles, button labels |
| `body` | 17 / 22 | Regular | Body copy |
| `callout` | 15 / 20 | Regular | Secondary rows, toasts |
| `caption` | 12 / 16 | Medium | Card metadata, badges, counts |
| `stamp` | 40 / 44 | Black, UPPERCASE, letter-spacing 2 | KEEP / DELETE / SKIP overlays |

Numbers that change (counters, progress) use tabular figures (`fontFeatureSettings = "tnum"`).

### 5.5 Spacing, shape, elevation

- **Spacing scale:** 4, 8, 12, 16, 24, 32, 48 dp. Screen side gutter: **16 dp**.
- **Corner radii:** card **24 dp**, sheet **20 dp**, grid tile **8 dp**, buttons fully round (pill or circle).
- **Touch targets:** at least 44 × 44 dp.
- **Elevation:** no shadows except on the top card, which gets a soft black shadow (blur 24 dp, 40% opacity). Layering uses `surface` / `surfaceRaised` steps instead.
- **Icons:** Material Symbols Rounded, 24 dp (28 dp in the swipe action bar). Mapping: Keep = `check`, Delete = `close`, Skip = `arrow_upward`, Undo = `undo`, Queue = `delete`, Hide = `visibility_off` (FR-38), Settings = `settings`, Back / close = `arrow_back`. Icons render through `dev.vicart:compose-material-symbols` (Rounded style) from the bundled Google variable fonts. In the FR-24 queue viewer the close affordance is `arrow_back`, never `close`: × means Delete in this app, and it must not sit beside the Restore check (WP-9.5).

### 5.6 Motion and gestures (swipe card)

The swipe card is the heart of the app. These numbers are the spec, not suggestions.

| Parameter | Value |
| --- | --- |
| Commit threshold, horizontal | 35% of card width **or** fling velocity ≥ 1000 dp/s |
| Commit threshold, up (Skip) | 25% of card height **or** upward fling ≥ 1000 dp/s |
| Rotation while dragging | `rotation = (dx / cardWidth) × 12°`, clamped to ±12°, pivot at the bottom center |
| Stamp opacity | Linear from 0 at 0 offset to 1 at the threshold |
| Stamp position | KEEP at the top-left, rotated −12°. DELETE at the top-right, rotated +12°. SKIP at the bottom center. 3 dp stroke in the verdict color, transparent fill |
| Edge glow | A gradient in the verdict color on the swipe side, max 30% alpha at the threshold |
| Fly-off on commit | 220 ms, `FastOutLinearInEasing`, continuing along the release direction until fully off-screen |
| Snap back (below threshold) | Spring, `dampingRatio = 0.7`, `stiffness = Spring.StiffnessMediumLow` |
| Card underneath | Scale 0.94 → 1.0 and alpha 0.6 → 1.0, tied to the top card's drag progress |
| Undo return | The card flies back in from the side it left, 260 ms, `FastOutSlowInEasing` |
| Hide advance (FR-38) | No fly-off: the asset is already hidden in the library when the deck moves, so the card leaves with the plain card swap |
| Hide undo return (FR-38) | The card returns **in place** where it stands — fade in with scale 0.92 → 1.0, 260 ms, `FastOutSlowInEasing` — not flying in from a side |
| Button-triggered action | Same fly-off, with a synthetic 30 dp nudge first (80 ms) so the motion reads |
| Haptics | `selection` when crossing the threshold in either direction. `impactLight` on Keep or Skip commit. `impactMedium` on Delete commit. `notificationSuccess` after a batch delete |
| Reduce Motion | No rotation or fly-off. A 150 ms crossfade to the next card. Stamps still show. The Hide undo return keeps its fade without the scale (FR-38). Screen transitions crossfade (150 ms) instead of sliding |

Other gestures on the card:

- **Tap**: on a video, toggles mute; on a photo, toggles the chrome (top bar and metadata) on and off.
- **Double-tap**: toggles between aspect-fit (default) and aspect-fill.
- **Long-press**: plays a Live Photo.
- **Horizontal drag always belongs to the swipe.** The video scrubber sits in the action-bar area, outside the card, so the two never conflict.

### 5.7 Components

| Component | Spec |
| --- | --- |
| `SwipeCard` | Full width minus 2 × 8 dp, height = space between top bar and action bar. Radius 24, `bg` fill. Media is aspect-fit by default. The bottom 96 dp has a `scrim` gradient with date (`caption`, `textPrimary`) and badges. |
| `VerdictStamp` | Bordered uppercase `stamp` text, 3 dp stroke, radius 8, 8/16 dp padding. |
| `ActionBar` | 3 circular buttons in a row, centered, 24 dp gap: Delete (64 dp, `delete @ 16%` fill, `delete` icon), Skip (48 dp, `surfaceRaised`, `skip` icon), Keep (64 dp, `keep @ 16%` fill, `keep` icon). Undo (40 dp) sits at the left edge of the bar. Share (40 dp, transparent, `textPrimary` `ios_share` icon, FR-32) mirrors it at the right edge; while the media loads it shows the `downloading` icon, reads "Preparing to share", and is disabled. |
| `TopBar` | 44 dp tall, transparent over `scrim`. Left: close (×). Center: progress `128 / 2,340` (`caption`, tnum) with a 2 dp progress line under it. Right: a **Hide** button (default touch-target size like its neighbors, `visibility_off`, VoiceOver label "Hide from library", FR-38) immediately left of the queue button with a red count badge. The Hide button is disabled while a hide or a decision write is in flight. |
| `QueueBadge` | `delete` pill, min 20 dp, `caption` white text. Shows 99+ above 99. |
| `SourceRow` (Home) | 64 dp row on `surface`: 40 dp thumbnail (radius 8), title `headline`, count `callout textSecondary`, chevron. On the Albums screen it grows a trailing **Exclude from Shuffle** switch (FR-37) before the chevron: the iOS-shape `SwipeSwitch` in a touch box `max(minTouchTarget, switch track width)` = 51 × 44 dp owning `Role.Switch` semantics — the 51 dp track fits its box instead of spilling toward the count (WP-8.14). `s` gaps separate count → switch and switch → chevron; rows without a switch keep the `xs` chevron spacing, so Home and By month render unchanged. An excluded album shows a "Not in Shuffle" subtitle under its title in `caption textSecondary` — the row stays 64 dp — and the subtitle joins the row's VoiceOver description (NFR-8). The switch's tap toggles the exclusion and never opens the row. |
| `SegmentedControl` (Home) | Full-width `surface` pill track; the All / Photos / Videos segments wrap their labels and sit **centered in the track** (FR-5), each a tab with a ≥ 44 dp touch target (§5.5). Selected segment fills `accent` with the label in `textPrimary`; unselected labels `textSecondary`. |
| `PrimaryButton` | 52 dp pill, full width minus gutters, `accent` fill, `headline` white (text: `textPrimary` — near-white token; no separate white token in v1). The destructive variant uses a `delete` fill. |
| `GridTile` | Square, radius 8, 2 dp gap. Video duration at the bottom-right in `caption`. When selected for restore: 3 dp `keep` ring. |
| `Banner` | `surfaceRaised` row, 4 dp leading bar in `warning`, `callout` text, trailing action in `accent`. |
| `Toast` | `surfaceRaised`, radius 12. One shared `ToastHost` renders at most one toast per screen, always bottom-anchored (§6.6): on the deck it **overlays** the card's bottom edge directly above the action bar — the card never resizes when a toast appears — elsewhere at the bottom of the screen clear of the Home Indicator. The host owns the 3 s auto-dismiss. Used for "Undone", "Hidden in Photos" (FR-38), and errors (save failed, share failed, delete failed, restore failed, hide failed); errors show plain user copy, never raw PhotoKit text. A stay-until-acted-on confirmation is **not** a toast — the FR-22 favorite confirm is the `FavoriteConfirmCard` below. |
| `FavoriteConfirmCard` (FR-22, WP-8.9) | The favorite-delete confirmation — a compact card, not a toast. Anchored at the bottom of the deck stage directly above the action bar (the deck toast's anchor), overlaying it so the photo being confirmed never resizes. `surfaceRaised`, sheet radius, `m` padding: title `headline` and one supporting line `callout textSecondary`, both centered. Buttons side by side at equal width with a hairline between, each a ≥ 44 dp target (§5.5): **Cancel** left in `accent` (the secondary text style), **Delete** right — the only element in the card wearing the `delete` verdict color. Nothing pre-selected, nothing auto-dismisses (NFR-5). VoiceOver: the title is announced as the card appears (assertive live region), and the buttons read "Cancel, keep this photo" / "Delete favorite" (NFR-8). Entrance fade 150 ms, skipped under Reduce Motion. |

### 5.8 Screens

**Screen map**

```
Home ─┬─► Albums ──────┐
      ├─► By month ────┤
      │                │
      │                ▼
      └──────────► Swipe ──► Session summary
                              │              │
                              ▼              ▼
                          Review queue ◄─────┘
                              │
                iOS confirm ──┴──► Delete success ──► Home
Home ──► Settings
```

First launch (FR-1): Home is the root from the first frame and shows its
placeholder state while the iOS access prompt is up. Don't Allow routes to the
denied screen (Open-Settings); every other answer stays on Home.

**Home**

```
┌──────────────────────────────┐
│ Swipe Cleaner            ⚙   │  title
│ 1,204 reviewed · 892 kept · 312 deleted │  callout, textSecondary (FR-10 lists all three; tabular figures)
│ ┌──────────────────────────┐ │
│ │ 🗑 18 waiting to delete › │ │  Banner (only when queue > 0)
│ └──────────────────────────┘ │
│ [ All | Photos | Videos ]    │  segmented control
│                              │
│ ▣ Shuffle everything  8,410 ›│  SourceRow (accent icon)
│ ▣ Albums                    ›│
│ ▣ By month                  ›│
│ SMART FILTERS                │  caption, textSecondary
│ ▣ Screenshots          1,022 ›│
│ ▣ Long videos             64 ›│
│ ▣ Bursts                 210 ›│
│ ▣ Likely duplicates      388 ›│
│ ▣ Selfies                540 ›│
└──────────────────────────────┘
```

**Albums**

One `SourceRow` per album (FR-8), user albums first. Each row also carries the
**Exclude from Shuffle** switch described in §5.7 (FR-37): toggling it takes the
album's items out of the Shuffle deck and refreshes Home's Shuffle count, but
never changes the album's own row count or what tapping the row opens.

Under the header, above the list, a `caption textSecondary` explainer shows
every time the screen opens (WP-8.14) — "Turn an album on to leave its photos
out of Shuffle everything." — because a switch that means *excluded* needs
saying, and the app has no onboarding to explain it. VoiceOver reads it as
normal text before the first row (NFR-8). An excluded row shows
"Not in Shuffle" under its title in `caption textSecondary`; the row stays
64 dp.

The scan's states (WP-9.6, FR-8, §6.8): while the first scan runs the screen
shows "Loading…" under the explainer — it opens at once and never shows a
number that is about to be replaced (NFR-4). An answered empty list shows
"No albums". A scan that cannot answer shows "Couldn't load albums" with a
**Try again** `PrimaryButton` that starts a fresh scan. The error state never
replaces shown rows: once the scan has answered once, a failed refresh keeps
the rows on screen for the next `changes()` retry.

```
┌──────────────────────────────────┐
│ ‹ Albums                         │  header
│ Turn an album on to leave its    │  explainer, caption textSecondary
│ photos out of Shuffle everything.│  (FR-37, shown every time)
│ ▣ Trip           1,204  [—●]  ›  │  excluded: switch on, s gaps around it
│    Not in Shuffle                │  caption textSecondary (FR-37)
│ ▣ Family        12,345  [●—]  ›  │  not excluded: no subtitle
└──────────────────────────────────┘
```

**By month**

Year → month buckets (FR-7), newest first, each `SourceRow` showing the exact
count of the deck its bucket opens (§6.5 *Counts*). The scan's states match
Albums' (WP-9.6, FR-7, §6.8): "Loading…" while the first scan runs — the
bucketing pass over a large library takes time and the screen says so —
"Nothing to show" when the scan answers with no photos, and
"Couldn't load your library" with a **Try again** `PrimaryButton` when it
cannot answer. As on Albums, an error never replaces rows the scan already
delivered; a failed refresh keeps them for the next `changes()` retry.

**Swipe**

```
┌──────────────────────────────┐
│ ×      128 / 2,340     ⊘ 🗑 18 │  TopBar: hide (FR-38), queue
│ ━━━━━━━━━━──────────────────  │  progress line
│ ┌──────────────────────────┐ │
│ │ KEEP                     │ │  stamp (fades in on drag)
│ │                          │ │
│ │        [ media ]         │ │  SwipeCard
│ │                          │ │
│ │ Mar 14, 2021 · 0:32  ☁ ◉ │ │  metadata on scrim
│ └──────────────────────────┘ │
│  ──●──────────── 0:12        │  video scrubber (videos only)
│ ↶     ( ✕ )  (↑)  ( ✓ )    ⇪ │  ActionBar: undo, delete, skip, keep, share (FR-32)
└──────────────────────────────┘
```

**Swipe — favorite confirm (FR-22)**: swiping a favorite left flies the card out and back,
then `FavoriteConfirmCard` rides the deck stage above the action bar. The photo stays
exactly as it was — the card overlays, it never resizes — and a fresh drag on the card
cancels the confirmation (§5.7).

```
┌──────────────────────────────┐
│        [ media ]             │  the pending card, returned to rest
│  ┌────────────────────────┐  │
│  │  Delete this favorite? │  │  title, headline, centered
│  │ Queues it with the     │  │  supporting line, callout
│  │ items waiting to be…   │  │
│  │   Cancel  │   Delete   │  │  equal cells ≥ 44 dp; Delete in `delete`
│  └────────────────────────┘  │  FavoriteConfirmCard, surfaceRaised
│ ↶     ( ✕ )  (↑)  ( ✓ )    ⇪ │  ActionBar
└──────────────────────────────┘
```

**Review queue**

```
┌──────────────────────────────┐
│ ‹ Back     To delete (18)    │
│ Tap an item to restore it.   │  callout, textSecondary
│ ┌──┐┌──┐┌──┐                 │
│ │  ││  ││  │                 │  3-column GridTile
│ └──┘└──┘└──┘                 │
│ ...                          │
│ ┌──────────────────────────┐ │
│ │     Delete 18 items      │ │  PrimaryButton (destructive), pinned
│ └──────────────────────────┘ │
│ They'll move to Recently     │  caption, centered
│ Deleted for 30 days.         │
└──────────────────────────────┘
```

The CTA and the §6.7 confirm title pluralize the count — "Delete 1 item" / "Delete 1 item?" for a single queued item, "Delete 18 items" / "Delete 18 items?" above it (WP-9.5).

**Queue item viewer** (FR-24, WP-9.5): a tile tap opens the item full-screen on `bg`. Videos **play** through the deck's `VideoPlayer` (muted, like FR-16) so an item can be checked before it is deleted; photos and Live Photos show the still. Top-left a back affordance (`arrow_back`, not ×, per §5.5) closes it; bottom-center the **Restore** pill (`check`, `keep @ 16%`) records KEEP. The viewer is modal while it is up: it consumes every tap, and the grid and the delete CTA behind it receive neither touches nor VoiceOver focus. The grid keeps its scroll position across open and close.

**Session summary / Delete success**: a centered `display` number with a verdict-colored label (`Kept 92`, `Queued 18`, `Skipped 4`), then a primary CTA ("Review 18 to delete" or "Back home"). On the delete-success screen, a partial batch (FR-26) adds one `callout` line in `textSecondary` below the count — "M couldn't be found" — where M is the number of ids that stayed queued.

**Empty states**: "Nothing left here 🎉" (the one allowed emoji) plus "Try another source" leading back to Home. On the review queue the CTA reads "Back home" instead — there is no other source to try from there (WP-3.3). While a deck is opening — until its first card is published, it summarizes, or the start fails — a raised card-shaped placeholder stands in for the deck instead ([SwipeUiState.loading], FR-6, WP-8.3): a slow first metadata page never flashes an empty state. The empty state therefore appears only when an opened deck has genuinely nothing left to review; ids whose metadata never resolves are dropped from the deck (§6.5, §6.8), not shown as blank cards.

---

## 6. Architecture

### 6.1 Overview

A single Compose Multiplatform app module with clean internal layers. The code depends inward: UI → ViewModel → Domain ← Data. Platform APIs live behind interfaces defined in `commonMain` and implemented in `iosMain`.

```
┌────────────────────────── commonMain ───────────────────────────┐
│  ui/ (Compose screens, theme, components)                       │
│        │  collects StateFlow<UiState>, sends intents            │
│        ▼                                                        │
│  presentation/ (ViewModels: Home, Swipe, Queue, Settings)       │
│        │                                                        │
│        ▼                                                        │
│  domain/ (models + use cases: BuildDeck, RecordDecision,        │
│           UndoDecision, CommitDeletions, LoadSources)           │
│        │ depends on interfaces ▼                                │
│  data/  MediaLibrary (interface)   DecisionStore (SQLDelight)   │
│         MediaLoader  (interface)   SettingsStore (interface)    │
└─────────────────────────────┬───────────────────────────────────┘
                              │ implemented by
┌──────────── iosMain ────────▼───────────────────────────────────┐
│  PhotoKitMediaLibrary  (PHAsset / PHFetchResult / PHPhotoLibrary)│
│  PhotoKitMediaLoader   (PHCachingImageManager → ImageBitmap)    │
│  VideoPlayerView       (AVPlayerLayer in UIKitView)             │
│  NSUserDefaultsSettingsStore, NativeSqliteDriver, Haptics       │
└─────────────────────────────────────────────────────────────────┘
          ▲ entry point: MainViewController() called from iosApp (Swift)
```

### 6.2 Tech stack

| Concern | Choice | Notes |
| --- | --- | --- |
| Language | Kotlin 2.x (latest stable) | K2 compiler |
| UI | Compose Multiplatform (latest stable) | iOS is stable since 1.8 |
| Navigation | `org.jetbrains.androidx.navigation:navigation-compose` | Type-safe routes with `@Serializable` |
| ViewModel | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` | |
| Async | kotlinx.coroutines + Flow | |
| DI | Koin (`koin-compose`, `koin-compose-viewmodel`) | Platform module bound in `iosMain` |
| Persistence | SQLDelight 2.x, `native-driver` | Small relational schema, proven on iOS |
| Settings | Interface + `NSUserDefaults` actual | Three booleans; no extra library needed |
| Photos | PhotoKit through Kotlin/Native `platform.Photos` | No Swift bridge needed |
| Video | `AVPlayer` + `AVPlayerLayer` in `UIKitView` | Kotlin/Native `platform.AVFoundation` |
| Images | `PHCachingImageManager` → JPEG bytes → `org.jetbrains.skia.Image.makeFromEncoded` → `ImageBitmap` | Decoded off the main thread |
| Tests | `kotlin.test`, `kotlinx-coroutines-test`, Turbine, Compose MP `ui-test` | Fakes for every platform interface (§8.2) |
| Lint | ktlint, detekt, Compose rules for detekt | See §8.3 |

Pin exact versions in `gradle/libs.versions.toml` when scaffolding, and check that the Compose MP, Kotlin, and Koin versions are compatible with each other.

### 6.3 Module and package layout

```
swipe-app/
├── REQUIREMENTS.md
├── AGENTS.md
├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
├── .editorconfig                       # ktlint settings (§8.3)
├── config/detekt/detekt.yml
├── scripts/                            # validate.sh, check-rules.sh, install-hooks.sh, git-hooks/, xcode-build-kotlin.sh
├── docs/                               # manual-test.md, fixtures/README.md
├── composeApp/
│   └── src/
│       ├── commonMain/kotlin/dev/swipe/
│       │   ├── App.kt                  # root composable, NavHost
│       │   ├── di/                     # Koin common module
│       │   ├── ui/theme/               # SwipeTheme, tokens (§5)
│       │   ├── ui/components/          # SwipeCard, ActionBar, ...
│       │   ├── ui/screens/{home,swipe,queue,summary,settings,onboarding}/
│       │   ├── presentation/           # ViewModels + UiState/Intent
│       │   ├── domain/model/           # MediaItem, Decision, DeckSource...
│       │   ├── domain/usecase/
│       │   └── data/                   # interfaces + SQLDelight store
│       ├── commonMain/sqldelight/dev/swipe/db/*.sq
│       ├── commonTest/                 # use-case + ViewModel tests
│       ├── iosMain/kotlin/dev/swipe/
│       │   ├── MainViewController.kt
│       │   ├── di/IosModule.kt
│       │   └── platform/               # PhotoKit*, VideoPlayerView, Haptics
│       └── androidMain/                # stub for later
└── iosApp/                             # Xcode project (Swift entry, Info.plist, signing)
```

Split into Gradle modules (`:core:domain`, `:core:data`, `:feature:*`) only if build times or Android work make it worthwhile. For a one-person app, packages are enough.

### 6.4 Key interfaces (commonMain)

```kotlin
interface MediaLibrary {                                    // read side only — no delete (WP-9.1, NFR-5); the write door is MediaDeleter below
    suspend fun authorization(): LibraryAccess            // FULL, LIMITED, DENIED, NOT_DETERMINED
    suspend fun requestAuthorization(): LibraryAccess
    suspend fun albums(): List<AlbumSource>                      // user + main smart albums, cheap counts & cover (FR-8, NFR-4)
    suspend fun months(type: MediaTypeFilter): List<MonthBucket> // creationDate scan, newest first — call lazily when By-month opens, never at startup (FR-7, NFR-4)
    suspend fun smartCounts(type: MediaTypeFilter): Map<SmartFilter, Int>  // per-filter counts (FR-6); no screen caller since WP-8.12 — every displayed count runs through DeckMembership (§6.5)
    suspend fun assetIds(source: DeckSource, type: MediaTypeFilter): List<String>  // ids only, cheap
    suspend fun items(ids: List<String>): List<MediaItem>  // metadata for a page
    fun changes(): Flow<Unit>                              // PHPhotoLibraryChangeObserver
    suspend fun duplicateCandidates(type: MediaTypeFilter): List<DuplicateKey>  // id+date+size only, date-sorted (FR-6)
}

interface MediaDeleter {                                    // the write door (WP-9.1, NFR-5): deliberately NOT on MediaLibrary, so readers — every ViewModel — cannot name a delete. The Koin graph binds it only into CommitDeletionsUseCase; nothing else in production may even reference the type (§8.3 check-rules, AGENTS.md rule 1). The second write path (FR-38 setHidden, WP-8.11) is `MediaHider` below, never a method added here.
    suspend fun delete(ids: List<String>): DeleteResult    // DELETED, CANCELLED, FAILED(msg) — one PhotoKit change request, one iOS system dialog (FR-25)
}

interface MediaLoader {
    /**
     * Emits a fast degraded image, then the full-quality one (NFR-1); closes after the full-quality
     * result. `targetPx` is an upper bound — the loader caps it at the screen's pixel size, so a
     * request never decodes more pixels than the display can show (NFR-2, R1). Full-screen-sized
     * (non-degraded) decodes go through an LRU of 5 keyed by `(id, targetPx)` (NFR-3, §6.5 step 3);
     * a cached decode at the same key is emitted directly, without a second PhotoKit request.
     * When two requests for one key overlap, the LRU keeps the decode it already holds and releases
     * the redundant one — a bitmap the cache handed out is closed only by eviction, never by a later
     * put — and the caller emits the value the LRU holds (NFR-3, WP-7.1).
     * Requests for a smaller target — grid thumbnails — bypass the LRU entirely, so scrolling a
     * grid never evicts the deck's cached full-screen decodes (NFR-3, WP-3.3).
     */
    fun image(id: String, targetPx: IntSize): Flow<LoadedImage>   // LoadedImage(bitmap, isDegraded, isCloud) — isCloud drives the cloud badge (R6)
    fun prefetch(ids: List<String>, targetPx: IntSize)
    fun cancelPrefetch(ids: List<String>, targetPx: IntSize)      // targetPx must match the prefetch being cancelled (PhotoKit matches by size)

    companion object {
        val FULL_TARGET_PX: IntSize                         // unbounded target; the loader caps it at screen px; cards and prefetch share it so prefetched decodes hit the card's cache key (NFR-1, NFR-3)
    }
}

interface VideoPreloader {                                      // §6.5 step 4; iosMain impl caches AVPlayerItem by asset id (VideoItemCache)
    fun preload(id: String)                                     // warm the playable item (networkAccessAllowed per includeCloudOnly); no-op when warm
    fun cancel(id: String)                                      // id left the prefetch window (step 3 parity); drops the warm item
}

interface DecisionStore {
    suspend fun record(id: String, decision: Decision, sessionId: String)  // SKIP goes to session_skip, never to decision (§7)
    suspend fun remove(id: String, sessionId: String)                       // undo; clears the decision row, or this session's session_skip row (FR-14: never another session's skip)
    suspend fun decidedIds(): Set<String>                   // KEEP + pending DELETE
    suspend fun sessionCounts(sessionId: String): SessionCounts  // this session's KEEP / pending-DELETE COUNTs + reviewed (every row, DELETED included; WP-6.4) — FR-21 summary; resume index correction, §6.5 step 1
    fun deleteQueue(): Flow<List<String>>
    suspend fun markDeleted(ids: List<String>)
    suspend fun dropFromQueue(ids: List<String>)                        // FR-28 prune; deletes only pending-DELETE rows — a KEEP or DELETED row is never touched (WP-7.6)
    suspend fun clearKeeps()
    fun stats(): Flow<Stats>                                // reviewed, kept, deleted, queued; COUNT queries, nothing stored (FR-10, §7)
}

interface SessionStore {                                    // session + session_skip rows (§7); timestamps from the injected kotlin.time.Clock
    suspend fun create(source: DeckSource, typeFilter: MediaTypeFilter, seed: Long): String
    suspend fun end(id: String)                                 // stamp ended_at: deck ran out (FR-21), or opened empty with nothing reviewed (WP-7.3)
    suspend fun endOtherOpenSessions(exceptId: String)        // stamp ended_at on every other open row: a fresh session closes the rows a source switch leaves behind, which can never be resumed (WP-9.2)
    suspend fun openSession(): SessionRecord?               // an undecodable row is ended on the spot and reported as none (WP-8.10)
    suspend fun skippedIds(sessionId: String): Set<String>  // skips of this session (FR-14)
}

interface ShuffleExclusionStore {                           // shuffle_exclusion table (§7); FR-37 — album ids only, never asset ids
    suspend fun excludedAlbumIds(): Set<String>             // excluded album ids; empty = nothing excluded
    fun excludedAlbumIdsFlow(): Flow<Set<String>>           // live view: the Albums switches and the Home refresh trigger
    suspend fun setExcluded(albumId: String, excluded: Boolean)  // switch write; a failed write leaves the switch where it was (NFR-7)
}

interface SettingsStore {                                   // NSUserDefaults; defaults haptics/includeCloudOnly/autoplayVideos = true
    val haptics: StateFlow<Boolean>                         // FR-29
    val includeCloudOnly: StateFlow<Boolean>                // FR-29
    val autoplayVideos: StateFlow<Boolean>                  // FR-29
    fun setHaptics(enabled: Boolean)
    fun setIncludeCloudOnly(enabled: Boolean)
    fun setAutoplayVideos(enabled: Boolean)
}

interface MediaSharer {                                     // FR-32; iosMain impl PhotoKitMediaSharer
    suspend fun share(id: String): ShareResult              // export originals to temp files on tap (networkAccessAllowed per includeCloudOnly), present UIActivityViewController; returns PRESENTED once the sheet is up or FAILED; temp files deleted when the sheet finishes
}

interface MediaHider {                                      // FR-38; iosMain impl PhotoKitMediaHider — the hide write door, sibling to MediaDeleter: on MediaLibrary's side it stays a reader. Koin binds it only into the swipe path (SwipeViewModel); §8.3 check-rules keep the type out of every other production file, and PhotoKitMediaHider's change block (PHAssetChangeRequest.hidden) is the only performChanges writer besides PhotoKitMediaLibrary's delete path
    suspend fun setHidden(id: String, hidden: Boolean): HideResult  // APPLIED once the change request completes, FAILED when the asset cannot be fetched or the change fails; never queues, never confirms, never reaches deleteAssets
}

interface Haptics {                                         // §5.6; silent while SettingsStore.haptics is off
    fun selection()                                         // threshold crossed
    fun impactLight()                                       // keep / skip committed
    fun impactMedium()                                      // delete committed
    fun notificationSuccess()                               // batch delete confirmed (FR-26)
}

interface SystemActions {
    fun openSettings()                                      // FR-3
    fun presentLimitedLibraryPicker()                       // FR-2
    fun isReduceMotionEnabled(): Flow<Boolean>              // §5.6, NFR-8
    fun appVersion(): String                                // FR-31
    fun signingExpiry(): Instant?                           // FR-31; null hides the row
}
```

Timestamps and clocks use `kotlin.time.Clock` injected through Koin — it is stable in the
Kotlin 2.4 stdlib, so no custom `Clock` interface is defined. `FakeClock` implements it in tests.

`VideoPlayer` is a composable `expect fun VideoPlayer(id: String, muted: Boolean, playing: Boolean, onProgress: (Float) -> Unit, modifier: Modifier = Modifier, livePhoto: Boolean = false, seekTarget: SeekTarget? = null)` with the `actual` in iosMain. It's the only `expect/actual` composable. Everything else is an interface injected through Koin, which keeps it easy to fake in tests. The `actual` hosts an `AVPlayerLayer` over an `AVPlayer` in a `UIKitView` (the shared `VideoItemCache` resolved through Koin, so preloaded items mount warm); it loops by seeking to zero on the item's did-play-to-end notification — `AVPlayerLooper` is unusable through this SDK's Kotlin/Native interop, where neither initialiser ever queued a copy (verified on the iOS 26.5 simulator) — mutes with `volume = 0` (`AVPlayer` has no mute flag), and maps `playing` to `play()`/`pause()` — `playWhenReady` is gone from the iOS 26 SDK and `play()` carries its semantics. `livePhoto = true` plays the asset's paired video instead: it is exported to a temp file via `PHAssetResourceManager.writeDataForAssetResource` and played once, no loop (FR-17). `onProgress` fires roughly every 100 ms with `videoProgressFraction(position, duration)` for the scrubber (FR-16), where `position` is the playhead inside the item, wrapped back to 0 on every loop iteration. `seekTarget` carries a monotonically increasing token plus a 0..1 fraction; each new token seeks the player to `seekPositionSeconds(fraction, duration)` once — the scrubber's drag-while-paused path (FR-16). **At rest only (R2):** the hosted player ignores `graphicsLayer` rotation and translation, so callers mount it only while the card is not dragging and keep the poster image up for every animated moment (wired in WP-4.2). The WP-4.2 wiring: `DeckStage` owns the per-card `DeckVideoState` (started, live-press, scrub fraction, seek token) and passes a `VideoPlayerSlot` seam down through `CardStack`; `TopCardPlayback` applies the R2 gate — the player mounts only while `cardState.isIdle`, a play affordance shows until the first tap when autoplay is off, and the FR-17 Live Photo overlay mounts on press and unmounts on release. The scrubber itself (`VideoScrubber`) sits outside the card in the action-bar area, for videos only.

### 6.5 Deck pipeline

1. `BuildDeckUseCase(source, typeFilter, seed)` takes the source's membership from one shared `DeckMembership` function (FR-9, FR-37, WP-8.10) — **asset IDs only** (`PHFetchResult`, which is fast even with 50k items) minus `decidedIds()`, and for `DeckSource.All` minus every item of any album excluded from Shuffle (FR-37; see below) — then orders them per source. The Home and Albums counts run through the same function, so a displayed count and the deck its row opens are the same query by construction (FR-9):
   - Shuffle: ordered by a **stable per-id key** — the splitmix64 hash of `(seed, id)` sorted ascending, with the id string itself breaking ties so the order is total and never depends on fetch order (the fetches are sorted by §6.5, but Shuffle must not rely on that). The seed is stored on the session row, so the order survives process death. Because the key is a property of `(seed, id)` alone, filtering commutes with ordering: subtracting decided ids (FR-9), subtracting FR-37 excluded-album ids, and items entering or leaving the library between sessions never move the survivors — a rebuild with the same seed reopens a resumed session on the same card (FR-20), where a positional `shuffled(Random(seed))` permutation would move every card after the first library edit. Resume recomputes everything from that rebuild; no per-card position is stored (the `session.position` column and its write path were dropped in WP-9.2 — resume never read it back).
   - Shuffle exclusions (FR-37): PhotoKit has no "not in album" predicate, so the deck subtracts the ids of every excluded album (each one ids-only fetch, NFR-4) after the ordering. An id whose album the user has since deleted matches nothing and is ignored. The subtraction applies only to `DeckSource.All`: opening an excluded album, By month, and the smart filters keep its items.
   - Long videos: duration descending. The library returns `Smart(LONG_VIDEOS)` ids **pre-sorted** duration-descending (a PhotoKit sort descriptor on `duration`), so the use case keeps the order it receives.
   - Likely duplicates: `duplicateCandidates(type)` (id + date + size only, date-sorted) run through `DuplicateGrouper` — runs of ≥ 2 items taken within 2 s of their predecessor with identical pixel dimensions, members next to each other. Decided ids are subtracted before grouping.
   - Everything else: creation date descending (the library returns that order).
2. `SwipeViewModel` holds `deck: List<String>`, `index`, and an `undoStack`. It loads metadata in pages of 20. An id whose page does not resolve is dropped from the deck rather than ending it or showing a blank card (FR-6, WP-8.3); drops sit at or beyond the loaded end, so undo entries behind the head are never disturbed.
3. Prefetching keeps the **current + next 3** items requested from `PHCachingImageManager` at screen pixel size (`deliveryMode = opportunistic`, `isNetworkAccessAllowed` per setting). The decoded bitmap cache is an LRU of 5.
4. Video: the next video's `AVPlayerItem` is created ahead of time, so playback starts as soon as its card is on top. `SwipeViewModel` calls `VideoPreloader.preload(id)` for video ids entering the same current + next 3 window (metadata `type == VIDEO` only) and `cancel(id)` for ids leaving it — alongside the image prefetch of step 3 (WP-4.1).
5. On swipe, the ViewModel saves to `DecisionStore` first, then advances `index`. If the save fails, it emits an error and reverts the card (NFR-7).

**Session lifecycle (FR-20, FR-21, WP-7.3, WP-9.2).** A session row is open from `create` until something stamps `ended_at`. Running the deck out ends it, after the FR-21 summary. Starting a fresh session ends every other still-open row (`endOtherOpenSessions`, after the new row is created so a failed create touches nothing): the row a source switch leaves behind can never be resumed — `openSession()` answers only the newest open row — so closing it keeps orphans and their `session_skip` rows from growing forever. Resume matching treats an album source as matching on the album **id only**: the stored row keeps the title the album had at `create`, and renaming the album must not strand the session (FR-20). A resume that rebuilds an empty deck ends it too: with cards already reviewed it retries the undelivered summary (FR-21), and with nothing reviewed it shows the §5.8 empty state and stamps `ended_at` right away (a zero-review session), so `openSession()` never holds a session that has nothing to resume (FR-20). A failed start leaves the row's state alone — the deck build may succeed on the next open; a failed end leaves the row open, and the next open retries. A row this build cannot decode (an unreadable `source` JSON or `type_filter`) is ended on the spot by `openSession()` and reported as no open session, so one corrupt row never blocks every future deck (WP-8.10).

**iCloud-only exclusion (FR-29, WP-6.1).** The "Include iCloud-only items" toggle blocks *loading*, not deck membership. `PHAsset.isCloudOnly` is cheap on an already-fetched asset, but the deck build is identifiers-only by design (WP-1.4), so a deck-time filter would need a per-asset metadata pass over the source (NFR-4) — ids therefore stay in the deck either way. The setting drives `isNetworkAccessAllowed` on every image request and `networkAccessAllowed` on video-item preloads (steps 3–4): with it off, an iCloud-only item never downloads and its card shows the degraded thumbnail (or the load-error placeholder when no local version exists) instead of fetching over the network; with it on, the original downloads behind the cloud badge as before.

**Counts (WP-8.12).** One `SourceCountsUseCase` produces every count the app shows — Home's Shuffle and Smart rows (`invoke(type)`), the Albums screen's rows (`albums(type)`), and the By month screen's buckets (`months(type)`) — and every count is **the size of the very deck its row opens**, computed through `DeckMembership.ids` (Likely Duplicates through `DeckMembership.duplicateDeckIds`, subtracting the decided set **before** grouping, exactly like `BuildDeckUseCase`; Shuffle through the same path with the FR-37 excluded-album items subtracted). A count and its deck are the same query by construction (FR-9), so no row can ever disagree with what tapping it opens — including By month, whose raw scan count previously over-reported every bucket holding a decided item.

**Final-or-placeholder, never an estimate (FR-5).** The raw→exact ladder is gone: PhotoKit's `estimatedAssetCount` and raw fetch sizes are never displayed — they ignore the type filter, include decided items, and can lag reality, and re-showing them on every type switch was the jump-to-the-total flicker. A row shows its placeholder until its exact count lands; a `changes()` or FR-37 re-run carries only final numbers, so a settled row never dips and is never replaced twice. The Flows still arrive progressively (NFR-4): the cheap identity reads first — `albums()` (titles + covers, no enumeration), `months(type)`'s bucket list — then one emission per row as its exact count lands. `HomeViewModel` merges arrival-over-held: an arrived count stands until a later run replaces it, an absent one keeps the placeholder or the held number.

Switching the type filter drops every count to its placeholder at once — never stale numbers under a new label (FR-5) — and late arrivals from a superseded filter are discarded, never landing on the new label. A failed fetch keeps the row's placeholder (or held number across a refresh) and is retried by the next refresh (§6.8). The one exception is a row-list screen's **initial scan** read (`albums()`, `months(type)`): it is thrown, not swallowed, so the screen can explain itself — `AlbumsUiState` and `MonthsUiState` carry a `ListStatus` (loading / ready / empty / error) and the error state offers **Try again** (WP-9.6, FR-7, FR-8). `HomeViewModel` re-runs the count Flow — and re-reads the authorization for the FR-2 banner — exactly once per `MediaLibrary.changes()` emission, once per FR-37 exclusion-store change, and on every `SetTypeFilter`. Home never calls `months(type)`: the By-month screen is FR-7 and pays for that scan only when opened (WP-5.3). `MediaLibrary.smartCounts` has no screen caller since WP-8.12 and is a cleanup candidate.

### 6.6 State shape (Swipe screen)

```kotlin
data class SwipeUiState(
    val current: CardState?,        // null when the deck is exhausted
    val next: CardState?,
    val progress: Progress,         // reviewed, total
    val queueCount: Int,
    val canUndo: Boolean,           // the undo stack is non-empty (FR-15)
    val muted: Boolean,             // videos start muted; ToggleMute flips it (FR-16)
    val toast: ToastMessage?,
    val finished: SessionSummary?,  // set once the deck runs out (FR-21)
    val revertToken: Long,          // bumps when a failed save sends the card back (NFR-7)
    val pendingFavoriteDelete: String?, // the favorite awaiting confirm, else null (FR-22)
    val sharing: Boolean,           // the current item's media is loading for the share sheet (FR-32)
    val hiding: Boolean,            // a hide write is in flight; the Hide button is disabled (FR-38)
    val deciding: Boolean,          // a decision write is in flight; the Hide button is disabled too (FR-38)
    val loading: Boolean,           // the deck is still opening; the placeholder card stands in (FR-6, WP-8.3)
)
sealed interface SwipeIntent {
    data class Decide(val id: String, val verdict: Decision) // Keep / Delete / Skip for card `id`
    data class Hide(val id: String) // FR-38: hide card `id` in the library; ignored when `id` is not the card on screen
    data object Undo
    data object ToggleMute
    data class ConfirmFavoriteDelete(val id: String)
    data object DismissToast
    data object Share
}
sealed interface ToastMessage { SaveFailed; Undone; Hidden; HideFailed; FavoriteConfirm; StartFailed; ShareFailed }
sealed interface SwipeEvent { data class ReturnCard(val decision: Decision); data object ReturnCardInPlace }
```

**Decisions name their card (FR-12, FR-13).** Every decision intent carries the id of the
card the action was performed on, and the ViewModel ignores a decision whose id is not the
card on screen — a tap or VoiceOver action that lands while a card is flying off is either
refused at the card (the button path only flies an at-rest card) or dropped by the
ViewModel, never recorded against the leaving card or the one behind it. The UI's button
and accessibility paths refuse to start a fly-off while the card is dragging, snapping
back, returning, or already flying off; the gesture path reads its commit callback at
release time, so the flight's commit names the card that was on screen when the finger
lifted. `ConfirmFavoriteDelete(id)` additionally requires `id == pendingFavoriteDelete`.

`ToastMessage` variants: `SaveFailed` (NFR-7, the decision write failed and the card
reverted), `Undone` (FR-15), `FavoriteConfirm` (FR-22 — the deck renders it as the §5.7
`FavoriteConfirmCard`, not a toast (WP-8.9): while it is up `pendingFavoriteDelete` holds
the id, and `ConfirmFavoriteDelete(id)` records the DELETE that the first tap refused to
record; starting a drag on the pending card is the U3 cancel — it clears the confirm
through `DismissToast` and records nothing, and a swipe completed after it is an ordinary
verdict), `StartFailed` (the session could not be opened or
resumed; the screen stays empty instead of crashing), `ShareFailed` (FR-32, the
current item's media could not be loaded for the share sheet), `Hidden` (FR-38 —
"Hidden in Photos", the 3 s toast after a successful hide advanced the deck), and
`HideFailed` (FR-38 — "Couldn't hide this item — try again", the card stays).

**One toast host (WP-9.3).** Every screen renders at most one toast through the shared
`ToastHost` (`ui/components/`), always bottom-anchored: on the deck it overlays the card's
bottom edge directly above the `ActionBar` — the card's size never depends on whether a
toast is up — and on the Queue and Settings screens it sits at the bottom of the screen.
The host owns the only auto-dismiss timer and visibility decision: the `Toast` composable
is pure presentation and holds no dismissal state of its own. Each screen keeps the toast
value in exactly one place — the deck shows `SwipeUiState.toast` and dismisses through
`SwipeIntent.DismissToast`; the Queue and Settings screens fold their one-shot `Channel`
events into a single nullable `ToastSpec` UI state and clear it on dismiss. A confirm-style
`ToastSpec` (`autoDismisses = false`) stays until its owner removes it. The FR-22 favorite
confirm is not a toast (WP-8.9): with `toast == FavoriteConfirm` and a non-null
`pendingFavoriteDelete` the deck renders the §5.7 `FavoriteConfirmCard` at the same anchor
— **Cancel** sends `DismissToast` (records nothing) and **Delete** sends
`ConfirmFavoriteDelete(id)` — and the card stays until the user acts, an Undo closes it,
or any deck move clears it. Error toasts show
plain user copy — PhotoKit's own text (e.g. `QueueEvent.ShowDeleteError.message`) stays
diagnostic and is never shown.

`Share` (FR-32) hands the current card's id to `MediaSharer.share` with `sharing` up
until the sheet is on screen or the load fails. It is single-flight (taps while
`sharing` are ignored), writes nothing to `DecisionStore` or `SessionStore`, and leaves
`index` and the undo stack alone. Every deck move cancels a share that is still
loading, so a sheet never opens for a card that has left.

`Hide(id)` (FR-38) is a decision-shaped intent that writes no decision. It clears a pending
FR-22 favorite confirm on that card (remembering it on the undo step), then runs
`MediaHider.setHidden(id, true)` single-flight — taps while a hide or a decision write is in
flight are ignored, and the button is disabled for the same reason (`hiding` / `deciding`).
An id that is no longer the card on screen is dropped like any other stale intent, and a
result that lands after the card has left never advances the deck twice. Success pushes an
undo step (no verdict), advances the deck — a plain card swap, no fly-off (§5.6) — and
shows `Hidden`; failure shows `HideFailed` and the card stays (NFR-7). Undoing that step
unhides exactly the id stored in the undo entry — never a re-resolved current card — and
sends `ReturnCardInPlace`; a failed unhide restores the entry and reports `SaveFailed`.

One-shot ViewModel → UI events ride a `Channel` exposed as `Flow<SwipeEvent>`, so each
event is delivered to exactly one collector and never replays on recomposition — unlike a
state field a late collector would read as still-pending. `ReturnCard(decision)` commands
the card to come back with [decision]'s exit animation (undo succeeding, FR-15, and a
Delete on a favorite being refused for confirmation, FR-22); `ReturnCardInPlace` commands
the in-place return after an FR-38 hide undo (§5.6). `revertToken` stays the one
exception: it predates the channel (WP-1.8) and the card keeps watching its increment.
`SwipeEvent` carries the domain `Decision`, not a UI type (layering, §6.1).

The drag offset and animation state stay inside the `SwipeCard` composable, as local UI state. The ViewModel only receives committed intents.

### 6.7 Deletion flow

```
Swipe left ──► DecisionStore.record(DELETE)  (queued, item still in library)
Review queue ──► "Delete N" ──► in-app confirm dialog ──► CommitDeletionsUseCase
     └─► MediaDeleter.delete(ids) ──► PHPhotoLibrary.performChanges { deleteAssets }
              ├─ success   ─► DecisionStore.markDeleted(resolved ids) ─► Success screen
              ├─ cancelled ─► no-op (queue unchanged)
              └─ error     ─► toast, queue unchanged
```

`CommitDeletionsUseCase` is the only consumer of `MediaDeleter`, and so the only caller
of `MediaDeleter.delete` (NFR-5). Since WP-9.1 that is no longer a convention: `delete`
is not on `MediaLibrary` — a compile-time fact, so no reader, every ViewModel included,
can express a delete — and `MediaDeleter` is bound in Koin only into the use case, with
production code outside the use case, its Koin binding, and the PhotoKit implementation
barred from even naming the type; those two are guarded by tests and the §8.3
check-rules.

The in-app confirmation is enforced by construction, not convention: the use case
takes an `InAppDeleteConfirmation` token whose constructor is `internal` with no
public construction path, and `QueueViewModel`'s `ConfirmDelete` — the dialog's
Delete tap — is the only code that builds one, from the whole queue as it stands
when Delete is tapped (FR-25). The use case intersects the token with the current
queue before calling PhotoKit once, so ids that left the queue while the dialog
was up are never deleted.

A commit whose batch is only partly resolvable (FR-26, WP-7.6) is a partial
success: PhotoKit resolves ids by `localIdentifier`, and an id it cannot fetch —
a burst frame hidden by the Photos app, or an asset outside a Limited selection —
was **not** deleted. Only the ids the library reports gone flip to `DELETED`; the
unresolved rows keep their `DELETE` decision and stay queued for a later retry,
and the success screen says "N deleted, M couldn't be found" with both counts on
the route.

A commit that ends up deleting nothing — every id was already gone outside the
app, pruned while the dialog was up (FR-28) — still succeeds, but quietly: the
success screen is for a count, so with neither a deleted nor a not-found count
the queue simply re-renders into its own empty state (WP-3.3). The
`DeleteSuccess` route therefore always carries a positive deleted-or-not-found
count.

### 6.8 iOS platform notes

- `Info.plist`: `NSPhotoLibraryUsageDescription` = "Swipe Cleaner shows your photos one at a time so you can keep or delete them."
- Register `PHPhotoLibraryChangeObserver`, and on a change refresh counts and prune deleted IDs. The observer lives inside `MediaLibrary.changes()` — a conflated cold Flow that registers an observer on each collect and unregisters it in `awaitClose`; consumers (the queue re-prune, the limited-count refresh) re-read what they need from each tick.
- The `performChanges { deleteAssets }` completion handler maps to `DeleteResult` by plain values: success → `Deleted(ids)` carrying exactly the ids the change request could resolve (FR-26, WP-7.6 — an id PhotoKit never fetched is not reported as deleted); the error for a dismissed iOS dialog — domain `PHPhotosErrorDomain`, code 3072 (`PHPhotosErrorUserCancelled`) → `Cancelled` (FR-27); anything else → `Failed(localizedDescription)`. A batch whose ids fetch zero assets answers `Deleted(all requested ids)` without a change request — no dialog for nothing to delete, which is also how a retried commit converges after a failed `markDeleted`. That zero-fetch answer is trusted only when PhotoKit actually answered (audit R1, WP-8.3): `delete` refuses with `Failed` unless read-write authorization is granted, treats a failed id fetch as `Failed` rather than "gone", and re-probes each id individually before the zero short-circuit, so a permission-stalled or errored fetch can never mark still-present photos DELETED.
- Every id → asset lookup (`fetchAssetsWithLocalIdentifiers`) goes through one shared helper with `includeAllBurstAssets = true` (FR-6, WP-8.3): the source fetches enumerate every burst frame, and the default (nil-options) view carries only one representative per burst, so nil-options lookups silently failed to resolve the burst ids the deck was built from — in metadata paging, image loading, video items, sharing, and deletes.
- Run PhotoKit fetches on `Dispatchers.Default`. Deliver image results onto a coroutine Flow, and cancel the `PHImageRequestID` when the collector is cancelled. Grid tiles rely on this: unmounting a scrolled-away tile cancels its thumbnail request (WP-3.3).
- Keep the app portrait-only in v1.

## 7. Data model and persistence

**Domain models**

```kotlin
data class MediaItem(
    val id: String,              // PHAsset.localIdentifier
    val type: MediaType,         // PHOTO, VIDEO, LIVE_PHOTO
    val createdAt: Instant?,
    val durationMs: Long?,
    val widthPx: Int, val heightPx: Int,
    val isFavorite: Boolean,
    val isScreenshot: Boolean,
    val burstId: String?,
    val isCloudOnly: Boolean?,   // unknown until a load is attempted
)
enum class Decision { KEEP, DELETE, SKIP, DELETED }
sealed interface DeckSource { All; Album(id, title); Month(year, month); Smart(kind) }
```

**SQLDelight schema** (`Decision.sq`, `Session.sq`, `ShuffleExclusion.sq`)

```sql
CREATE TABLE decision (
  asset_id    TEXT NOT NULL PRIMARY KEY,
  decision    TEXT NOT NULL,          -- KEEP | DELETE | DELETED   (SKIP is never stored here)
  decided_at  INTEGER NOT NULL,       -- epoch ms
  session_id  TEXT NOT NULL
);
CREATE INDEX decision_by_type ON decision(decision);

CREATE TABLE session (
  id          TEXT NOT NULL PRIMARY KEY,
  source      TEXT NOT NULL,          -- serialized DeckSource
  type_filter TEXT NOT NULL,
  seed        INTEGER NOT NULL,
  started_at  INTEGER NOT NULL,
  ended_at    INTEGER
);

CREATE TABLE session_skip (
  session_id  TEXT NOT NULL,
  asset_id    TEXT NOT NULL,
  PRIMARY KEY (session_id, asset_id)
);

CREATE TABLE shuffle_exclusion (                          -- FR-37; one row per album excluded from Shuffle
  album_id    TEXT NOT NULL PRIMARY KEY                   -- album id only; a deleted album's stale id is ignored
);
```

- Stats (reviewed, kept, deleted) are computed with `COUNT` queries; nothing is stored separately.
- Two synthetic `session_id` values mark store writes that belong to no swipe session: `"restore"` (FR-24, queue restore recorded as KEEP) and, until WP-7.6, `"prune"` (FR-28). The prune no longer needs one: it goes through `DecisionStore.dropFromQueue`, whose `WHERE decision = 'DELETE'` guard retires the synthetic `"prune"` id — no session argument exists to fake, and a KEEP row cannot be erased by a prune whatever the row holds when the write lands.
- The database file lives in the app's Application Support directory and is excluded from iCloud backup.
- Schema changes use SQLDelight migrations (`1.sqm`, ...).

## 8. Build, install, test and validate

### 8.1 Prerequisites and install

**Prerequisites**: macOS with Xcode 26+ selected with `xcode-select` (the Command Line Tools alone can't build Kotlin/Native or run simulators), an iOS Simulator runtime, JDK 21 (pinned in `mise.toml`), Android Studio or IntelliJ IDEA with the Kotlin Multiplatform plugin, and an iPhone on iOS 17+.

**Installing with a free Apple ID**

1. In Xcode → Settings → Accounts, add your Apple ID.
2. Open `iosApp/iosApp.xcodeproj`. Under Signing & Capabilities, pick your Personal Team and set a unique bundle ID (for example `dev.<you>.swipecleaner`).
3. On the iPhone, turn on Settings → Privacy & Security → **Developer Mode**, then restart.
4. Select the device in Xcode and press Run.
5. On the phone, trust the developer under Settings → General → VPN & Device Management.
6. **Every 7 days**, plug in and press Run again to re-sign. App data is kept.

Limits of free provisioning: up to 3 apps per device, no TestFlight, and no paid-only entitlements (none are needed here).

**Installing with TestFlight (paid Apple Developer account)**

1. Set your team ID and registered bundle ID in `iosApp/Configuration/Local.xcconfig`, and create the app in App Store Connect with that bundle ID.
2. Raise the build number (`CURRENT_PROJECT_VERSION`) for every upload.
3. In Xcode, select **Any iOS Device (arm64)**, choose Product → Archive, then Distribute App → TestFlight & App Store.

Xcode Cloud builds need no version edits: `iosApp/ci_scripts/ci_post_clone.sh` installs the JDK and stamps `MARKETING_VERSION` as `<major>.<minor>.<Xcode Cloud build number>` in the build's checkout only, and Xcode Cloud assigns the build number. Change major or minor in the Xcode project. Set `SWIPE_TEAM_ID` and `SWIPE_BUNDLE_ID` as workflow environment variables.

Release metadata lives in `iosApp/iosApp/PrivacyInfo.xcprivacy`, which must declare any required-reason API the app uses (currently `NSUserDefaults`), and `iosApp/Info.plist`, which declares no non-exempt encryption. Revisit both if NFR-6 or the app's API use changes.

### 8.2 Testing

**Levels**

| Level | What | Where |
| --- | --- | --- |
| Unit | Use cases (deck ordering, duplicate grouping, undo, commit outcomes) | `commonTest`, fakes for `MediaLibrary` / `DecisionStore` |
| ViewModel | Intent → state transitions, persist-before-advance, revert on failure | `commonTest` + Turbine |
| Component | `SwipeCard` commit thresholds and snap-back, `ActionBar` buttons firing the right intent, VoiceOver content descriptions on every control | `commonTest` with `runComposeUiTest` (Compose MP UI test), run on the iOS simulator target |
| DB | SQLDelight queries and migrations | `iosSimulatorArm64Test` with an in-memory native driver |
| Manual | Gestures, haptics, video, the iOS delete dialog, limited access, iCloud-only items | Device checklist in `docs/manual-test.md` (created in M2) |
| Performance | NFR-1 to NFR-4 on a library of 10k+ items | Instruments (Time Profiler, Allocations, Animation Hitches) on a physical device, in M6 and after any change to the image pipeline |

**Rules for tests**

- **Name tests after requirements.** Every FR or NFR that has logic behind it gets at least one test whose name starts with its ID, for example `FR-15 undo restores last card`. Searching the tests for an ID shows how it is covered.
- **Safety rules have dedicated tests.** These must always exist and pass:
  - NFR-5: deleting with no in-app confirmation does nothing, and `CommitDeletionsUseCase` is the only consumer of `MediaDeleter` (WP-9.1: no ViewModel resolves it in the real graph — `DiSmokeTest`; no production class may name it — check-rules).
  - NFR-7: when `DecisionStore.record` fails, the index doesn't advance and the card comes back.
  - FR-27: when the iOS dialog is cancelled, the queue stays exactly as it was.
- **Use fakes, not mocks.** `commonTest/kotlin/dev/swipe/fakes/` holds `FakeMediaLibrary`, `FakeMediaLoader`, `FakeDecisionStore`, `FakeSettingsStore` and `FakeClock`. Each fake records its calls and can be set up to fail, so tests can cover error paths. Don't use mocking libraries.
- **Tests are deterministic.** Use `runTest` with virtual time, an injected `Clock`, and fixed shuffle seeds. Never use real delays, the wall clock, or random seeds that aren't fixed.
- **No PhotoKit in automated tests.** The `iosMain/platform/` implementations are covered by the manual checklist and by a simulator run with a seeded library. Keep them thin, so that the logic sits in tested `commonMain` code.
- **No coverage gate in v1.** Kover only measures JVM targets, and this app has none. Coverage comes from the per-requirement naming rule above instead.

Seed the simulator by dragging photos and videos into the Photos app, or by using `xcrun simctl addmedia booted <files>`. Keep a small fixture set in `docs/fixtures/` covering a screenshot, a burst, a pair of likely duplicates, a video over 60 s, and a Live Photo. The media files themselves are gitignored. Only a README listing what to collect is committed.

### 8.3 Linting and static checks

| Tool | Purpose | Gradle task |
| --- | --- | --- |
| ktlint 1.8 (`org.jlleitschuh.gradle.ktlint`) | Formatting, using the official Kotlin style | `ktlintCheck`, with `ktlintFormat` to fix |
| detekt 2.x (`dev.detekt`; 2.0 is the line that supports Kotlin 2.4 and that the Compose rules build against) | Static analysis: complexity, potential bugs, and forbidden imports | `detekt` |
| Compose rules (`io.nlopez.compose.rules:detekt`) | Compose-specific checks, such as modifier ordering, state hoisting, and no ViewModels passed down the tree | runs as part of `detekt` |
| `scripts/check-rules.sh` | Grep-based guards for the AGENTS.md hard rules (see below) | run directly |

- Pin every tool version in `gradle/libs.versions.toml`.
- Put the shared settings in `.editorconfig` at the repo root. It sets `ktlint_code_style = ktlint_official` and `ktlint_function_naming_ignore_when_annotated_with = Composable`.
- Keep the detekt config in `config/detekt/detekt.yml`. It starts from the defaults (`buildUponDefaultConfig = true`), and `FunctionNaming` ignores `@Composable`.
- Configure the `detekt` task to scan every source set (`src/*/kotlin`), not just the defaults.
- No baseline files. The codebase starts clean, so fix findings rather than suppressing them. Any `@Suppress` needs a comment saying why.
- Generated code (SQLDelight output, `build/`) is excluded from both tools.

**`scripts/check-rules.sh`** turns the hard rules into checks that fail the build. It exits non-zero and prints the file and line when any of these match:

| Check | Rule |
| --- | --- |
| `import platform.` anywhere under `src/commonMain` | Layering (§6.1) |
| `Color(0x` outside `ui/theme/` | Design tokens only (§5.2) |
| A raw `<number>.dp` or `<number>.sp` other than `0.dp`, outside `ui/theme/` and `ui/components/swipe/SwipeSpec.kt` | Design tokens only (§5.5) and gesture spec (§5.6) |
| `deleteAssets` or `performChanges` outside `PhotoKitMediaLibrary.kt` | NFR-5 |
| `MediaDeleter` named in code of a production (`src/*Main`) file other than `MediaDeleter.kt`, `CommitDeletionsUseCase.kt`, `IosModule.kt`, or `PhotoKitMediaLibrary.kt` (comment-only mentions don't count; `MediaLibrary` has no `delete`, so readers cannot call one at all — WP-9.1) | NFR-5 |
| Imports of `io.ktor`, `NSURLSession`, `NSURLConnection`, or any analytics or crash-reporting SDK | NFR-6 |
| A `DEVELOPMENT_TEAM` value or a personal bundle ID in any file tracked by git | Signing stays local |

### 8.4 Validating a change

**`scripts/validate.sh` is the definition of done.** It runs the steps below in order and stops at the first failure. A code change isn't complete until it passes. Docs-only changes (markdown or `docs/` files only) skip it.

| # | Step | Command |
| --- | --- | --- |
| 1 | Format | `./gradlew ktlintCheck` |
| 2 | Static analysis | `./gradlew detekt` |
| 3 | Hard-rule guards | `scripts/check-rules.sh` |
| 4 | Compile | `./gradlew :composeApp:compileKotlinIosSimulatorArm64` |
| 5 | Schema migrations | `./gradlew :composeApp:verifySqlDelightMigration` |
| 6 | Tests | `./gradlew :composeApp:iosSimulatorArm64Test` |
| 7 | App build (`--full` only) | `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' -derivedDataPath build/xcode build` |

Use `scripts/validate.sh --full` before merging to `main`, and after any change to `iosApp/`, `iosMain/`, or the Gradle build.

**Extra validation by type of change**

| Change touches | Also do |
| --- | --- |
| Use case or ViewModel | Add or update a `commonTest` named with the requirement ID |
| `.sq` files | Add a `.sqm` migration, regenerate the interface, commit the new schema `.db` snapshot, and make sure step 5 passes |
| `ui/theme/` or `SwipeSpec.kt` | Update §5 in the same change |
| Screens or components | Run on the simulator and check the screen against its wireframe in §5.8. Check that VoiceOver labels exist |
| Gestures or animation | Run on a physical device (the simulator doesn't show 120 Hz or haptics). Repeat the swipe section of `docs/manual-test.md` |
| `iosMain/platform/` (PhotoKit, video) | Run on the simulator with the seeded fixtures, then on a device. Check full, limited and denied access |
| Deletion path | Repeat the delete section of `docs/manual-test.md` on a device: confirm, cancel and error, each with the iOS dialog |
| Image pipeline or prefetch | Profile against NFR-1 to NFR-4 with Instruments |
| Dependencies | Bump versions only in `libs.versions.toml`, and check that Kotlin, Compose MP, Koin and SQLDelight are still compatible |

**Automation.** There's no CI in v1, because there's no remote. A git `pre-push` hook (`scripts/install-hooks.sh` installs it) runs `scripts/validate.sh`. If a GitHub remote is added, a macOS GitHub Actions job runs `scripts/validate.sh --full`.

## 9. Milestones

| # | Milestone | Done when |
| --- | --- | --- |
| M0 | Scaffold | KMP + Compose MP project builds and runs "Hello" on simulator and device. Theme tokens are in place. Lint config, `scripts/check-rules.sh` and `scripts/validate.sh` are in place, and `validate.sh --full` passes. |
| M1 | Library access | Permission flow (FR-1–3). Shuffle-all deck shows real photos. Image pipeline meets NFR-1. |
| M2 | Swiping | Full gesture spec (§5.6), Keep / Delete / Skip / Undo, persisted decisions, summary screen. |
| M3 | Review and delete | Queue grid, restore, batch delete with all three outcomes (FR-23–28). |
| M4 | Video and Live Photos | Autoplay, mute, scrubber, prefetch. Live Photo long-press. |
| M5 | Sources and filters | Albums, months, smart filters, type toggle, Home stats. |
| M6 | Polish | Settings, accessibility pass, Reduce Motion, performance check against the NFRs on a 10k+ library. |

## 10. Risks

| ID | Risk | Mitigation |
| --- | --- | --- |
| R1 | Compose MP on iOS janks during a drag while images decode | Decode off the main thread, cap the bitmap size to the screen, profile with Instruments in M1. **Programmatic check passed (M1, WP-1.9):** on the iPhone 17 simulator with a 119-asset library, 60 concurrent full-size decodes all ran off the main thread (0 on main), and the asset list for 119 assets took 50 ms — well under the 500 ms NFR-1 full-quality bound. Visual confirmation pending 👤: drag smoothness on a real device with Instruments (NFR-1). |
| R2 | Video interop (`UIKitView`) clips badly or ignores card rotation and gestures | **Confirmed (M1 spike, branch `spike-video-interop`):** rounded clipping works, but `graphicsLayer` rotation/translation are ignored — the player view stays axis-aligned at its layout rect. The fallback is needed: show the poster frame while dragging and the live player only when the card is at rest. **WP-4.1 outcome:** `VideoPlayer` documents the at-rest-only contract in its KDoc; the poster-while-dragging wiring (mount gating, autoplay-off tap affordance) lands in WP-4.2. |
| R3 | No public API for asset file size (`PHAssetResource` "fileSize" is undocumented KVC) | Storage estimate is optional (open question). Never block a feature on it. |
| R4 | The 7-day free signing expiry interrupts use | Documented re-sign step. Switch to a paid account if this gets annoying. |
| R5 | Accidental mass deletion | Batch confirm in the app plus the iOS dialog, Recently Deleted for 30 days, and a single delete code path (NFR-5). |
| R6 | iCloud-only items load slowly | Show the degraded image and a cloud badge, prefetch further ahead, and offer a setting to exclude them. |
