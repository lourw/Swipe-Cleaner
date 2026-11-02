# Swipe Cleaner — Requirements, Design & Architecture

_Last updated: 2026-11-01 · Status: approved for implementation_

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

- No App Store release, accounts, cloud sync, or analytics.
- No editing, sharing, favoriting, or album management.
- No ML-based duplicate or blur detection. Smart filters use metadata only.

## 2. Decisions

| Topic | Decision | Consequence |
| --- | --- | --- |
| UI stack | Compose Multiplatform shared UI | All screens are written in Kotlin. Video playback uses a UIKit interop view. PhotoKit is called from Kotlin/Native (`platform.Photos`). |
| Deletion | Batch delete at the end | Left swipes go into a persisted queue. A single `PHAssetChangeRequest.deleteAssets` call means a single iOS confirmation dialog. |
| Deck scope | Shuffle, album or month, smart filters | The default deck is the whole library, shuffled. Picking a source or a filter narrows it. |
| Install | Free Apple ID through Xcode | Builds must be re-signed every 7 days. No TestFlight. No paid entitlements are needed. |
| Min OS | iOS 17.0 | Uses modern PhotoKit, including limited-library APIs. |
| Platforms | iOS only for v1 | `androidMain` exists as a stub so the structure stays honest. |

**Open questions (the default in brackets is what we build unless someone changes it)**

- [ ] Long-press on a Live Photo plays its motion? **[Yes]**
- [ ] Include iCloud-only originals that aren't on the device? **[Yes. Allow network access, and show a cloud badge while the item loads.]**
- [ ] Show an estimate of storage freed? **[Only if the file size can be read reliably. See Risk R3.]**

## 3. Functional requirements

IDs are stable. Reference them in commits and tests, for example `FR-12`.

### Permissions and onboarding
- **FR-1** On first launch, show an onboarding screen that explains what the app does, then request `PHAccessLevel.readWrite`.
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

### Swiping
- **FR-11** The deck shows one item full-bleed, with the next item rendered underneath it.
- **FR-12** Right swipe or the Keep button records `KEEP` and advances to the next item.
- **FR-13** Left swipe or the Delete button records `DELETE` (queued, not yet deleted) and advances.
- **FR-14** Up swipe or the Skip button records `SKIP` for this session only. The item can appear in later sessions.
- **FR-15** **Undo** reverts the last decision and brings the card back. The undo stack holds up to 50 steps per session.
- **FR-16** Videos autoplay muted and loop. Tapping the video toggles sound, and the mute state is remembered for the session. A scrubber shows progress.
- **FR-17** Long-press on a Live Photo plays its motion.
- **FR-18** The card shows metadata: date, and the duration for videos. A badge marks Live Photos, iCloud items, and favorites.
- **FR-19** The progress indicator shows `reviewed / total` for the session.
- **FR-20** Every decision is saved immediately, so killing the app loses nothing.
- **FR-21** When the deck runs out, show a session summary: kept, queued for deletion, skipped, and a CTA to review the queue.
- **FR-22** A favorited item (`PHAsset.isFavorite`) swiped left shows an inline confirm toast, "This is a favorite — delete anyway?", before it is queued.

### Review and delete
- **FR-23** The Review Queue shows queued items in a 3-column grid, newest decision first.
- **FR-24** Tapping a tile opens it full-screen. That view has a **Restore** action, which removes it from the queue and records `KEEP`.
- **FR-25** "Delete N items" calls one `PHPhotoLibrary.performChanges { deleteAssets }` for the whole queue, which triggers the iOS system dialog.
- **FR-26** If the user confirms in iOS, the items are marked `DELETED` in the database and removed from the queue, and a success screen appears.
- **FR-27** If the user cancels the iOS dialog, the queue stays as it was and no error is shown.
- **FR-28** Queued items whose assets no longer exist (deleted elsewhere) are pruned silently whenever the queue is loaded.

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
| NFR-5 | Safety | The only code path that deletes assets is `CommitDeletionsUseCase`, and it requires the in-app confirmation. |
| NFR-6 | Privacy | No network calls except PhotoKit's iCloud downloads. No analytics. All data stays on the device. |
| NFR-7 | Durability | Decisions are written to SQLite before the UI advances. If that write fails, the swipe is reverted. |
| NFR-8 | Accessibility | Every swipe action is also available as a button. VoiceOver labels on all controls. Honours Reduce Motion (see §5.6). |

---

## 5. UI specification and design language

### 5.1 Principles

1. **The photo is the interface.** Chrome is minimal, translucent, and stays at the edges.
2. **One decision at a time.** Every screen has one obvious primary action.
3. **Color means verdict.** Green means keep, red means delete, and grey means skip. These colors are never used for anything else.
4. **Reversible by default.** Undo is always one tap away, and nothing destructive happens without confirmation.

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
- **Icons:** Material Symbols Rounded, 24 dp (28 dp in the swipe action bar). Mapping: Keep = `check`, Delete = `close`, Skip = `arrow_upward`, Undo = `undo`, Queue = `delete`, Settings = `settings`.

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
| Button-triggered action | Same fly-off, with a synthetic 30 dp nudge first (80 ms) so the motion reads |
| Haptics | `selection` when crossing the threshold in either direction. `impactLight` on Keep or Skip commit. `impactMedium` on Delete commit. `notificationSuccess` after a batch delete |
| Reduce Motion | No rotation or fly-off. A 150 ms crossfade to the next card. Stamps still show |

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
| `ActionBar` | 3 circular buttons in a row, centered, 24 dp gap: Delete (64 dp, `delete @ 16%` fill, `delete` icon), Skip (48 dp, `surfaceRaised`, `skip` icon), Keep (64 dp, `keep @ 16%` fill, `keep` icon). Undo (40 dp) sits at the left edge of the bar. |
| `TopBar` | 44 dp tall, transparent over `scrim`. Left: close (×). Center: progress `128 / 2,340` (`caption`, tnum) with a 2 dp progress line under it. Right: queue button with a red count badge. |
| `QueueBadge` | `delete` pill, min 20 dp, `caption` white text. Shows 99+ above 99. |
| `SourceRow` (Home) | 64 dp row on `surface`: 40 dp thumbnail (radius 8), title `headline`, count `callout textSecondary`, chevron. |
| `PrimaryButton` | 52 dp pill, full width minus gutters, `accent` fill, `headline` white (text: `textPrimary` — near-white token; no separate white token in v1). The destructive variant uses a `delete` fill. |
| `GridTile` | Square, radius 8, 2 dp gap. Video duration at the bottom-right in `caption`. When selected for restore: 3 dp `keep` ring. |
| `Banner` | `surfaceRaised` row, 4 dp leading bar in `warning`, `callout` text, trailing action in `accent`. |
| `Toast` | Bottom, above the action bar, `surfaceRaised`, radius 12, auto-dismisses after 3 s. Used for "Undone" and the favorite confirm. |

### 5.8 Screens

**Screen map**

```
Onboarding ──► Permission ──► Home ─┬─► Albums ──────┐
                  │                 ├─► By month ────┤
             (denied)               ├─► Smart filters┤
                  ▼                 │                ▼
           Open-Settings            └──────────► Swipe ──► Session summary
                                                   │              │
                                                   ▼              ▼
                                               Review queue ◄─────┘
                                                   │
                                     iOS confirm ──┴──► Delete success ──► Home
Home ──► Settings
```

**Home**

```
┌──────────────────────────────┐
│ Swipe Cleaner            ⚙   │  title
│ 1,204 reviewed · 312 deleted │  callout, textSecondary
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

**Swipe**

```
┌──────────────────────────────┐
│ ×      128 / 2,340      🗑 18 │  TopBar
│ ━━━━━━━━━━──────────────────  │  progress line
│ ┌──────────────────────────┐ │
│ │ KEEP                     │ │  stamp (fades in on drag)
│ │                          │ │
│ │        [ media ]         │ │  SwipeCard
│ │                          │ │
│ │ Mar 14, 2021 · 0:32  ☁ ◉ │ │  metadata on scrim
│ └──────────────────────────┘ │
│  ──●──────────── 0:12        │  video scrubber (videos only)
│ ↶     ( ✕ )  (↑)  ( ✓ )      │  ActionBar: undo, delete, skip, keep
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

**Session summary / Delete success**: a centered `display` number with a verdict-colored label (`Kept 92`, `Queued 18`, `Skipped 4`), then a primary CTA ("Review 18 to delete" or "Back home").

**Empty states**: "Nothing left here 🎉" (the one allowed emoji) plus "Try another source" leading back to Home.

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
interface MediaLibrary {
    suspend fun authorization(): LibraryAccess            // FULL, LIMITED, DENIED, NOT_DETERMINED
    suspend fun requestAuthorization(): LibraryAccess
    suspend fun sources(): List<DeckSource>                // albums, months, smart filters w/ counts
    suspend fun assetIds(source: DeckSource, type: MediaTypeFilter): List<String>  // ids only, cheap
    suspend fun items(ids: List<String>): List<MediaItem>  // metadata for a page
    suspend fun delete(ids: List<String>): DeleteResult    // DELETED, CANCELLED, FAILED(msg)
    fun changes(): Flow<Unit>                              // PHPhotoLibraryChangeObserver
    suspend fun duplicateCandidates(type: MediaTypeFilter): List<DuplicateKey>  // id+date+size only, date-sorted (FR-6)
}

interface MediaLoader {
    /** Emits a fast degraded image, then the full-quality one. */
    fun image(id: String, targetPx: IntSize): Flow<ImageBitmap>
    fun prefetch(ids: List<String>, targetPx: IntSize)
    fun cancelPrefetch(ids: List<String>)
}

interface DecisionStore {
    suspend fun record(id: String, decision: Decision, sessionId: String)  // SKIP goes to session_skip, never to decision (§7)
    suspend fun remove(id: String)                          // undo; clears the decision or session_skip row
    suspend fun decidedIds(): Set<String>                   // KEEP + pending DELETE
    fun deleteQueue(): Flow<List<String>>
    suspend fun markDeleted(ids: List<String>)
    suspend fun clearKeeps()
    fun stats(): Flow<Stats>                                // reviewed, kept, deleted, queued; COUNT queries, nothing stored (FR-10, §7)
}

interface SessionStore {                                    // session + session_skip rows (§7); timestamps from the injected kotlin.time.Clock
    suspend fun create(source: DeckSource, typeFilter: MediaTypeFilter, seed: Long): String
    suspend fun updatePosition(id: String, position: Int)   // resume position (FR-20)
    suspend fun end(id: String)
    suspend fun openSession(): SessionRecord?
    suspend fun skippedIds(sessionId: String): Set<String>  // skips of this session (FR-14)
}

interface SettingsStore {                                   // NSUserDefaults; defaults haptics/includeCloudOnly/autoplayVideos = true, onboardingSeen = false
    val haptics: StateFlow<Boolean>                         // FR-29
    val includeCloudOnly: StateFlow<Boolean>                // FR-29
    val autoplayVideos: StateFlow<Boolean>                  // FR-29
    val onboardingSeen: StateFlow<Boolean>                  // FR-1
    fun setHaptics(enabled: Boolean)
    fun setIncludeCloudOnly(enabled: Boolean)
    fun setAutoplayVideos(enabled: Boolean)
    fun setOnboardingSeen(seen: Boolean)
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

`VideoPlayer` is a composable `expect fun VideoPlayer(id: String, muted: Boolean, playing: Boolean, onProgress: (Float) -> Unit)` with the `actual` in iosMain. It's the only `expect/actual` composable. Everything else is an interface injected through Koin, which keeps it easy to fake in tests.

### 6.5 Deck pipeline

1. `BuildDeckUseCase(source, typeFilter, seed)` fetches **asset IDs only** for the source (`PHFetchResult`, which is fast even with 50k items), subtracts `decidedIds()`, then orders them:
   - Shuffle: a seeded shuffle (`Random(seed)`), with the seed stored on the session so the order survives process death.
   - Long videos: duration descending. The library returns `Smart(LONG_VIDEOS)` ids **pre-sorted** duration-descending (a PhotoKit sort descriptor on `duration`), so the use case keeps the order it receives.
   - Likely duplicates: `duplicateCandidates(type)` (id + date + size only, date-sorted) run through `DuplicateGrouper` — runs of ≥ 2 items taken within 2 s of their predecessor with identical pixel dimensions, members next to each other. Decided ids are subtracted before grouping.
   - Everything else: creation date descending (the library returns that order).
2. `SwipeViewModel` holds `deck: List<String>`, `index`, and an `undoStack`. It loads metadata in pages of 20.
3. Prefetching keeps the **current + next 3** items requested from `PHCachingImageManager` at screen pixel size (`deliveryMode = opportunistic`, `isNetworkAccessAllowed` per setting). The decoded bitmap cache is an LRU of 5.
4. Video: the next video's `AVPlayerItem` is created ahead of time, so playback starts as soon as its card is on top.
5. On swipe, the ViewModel saves to `DecisionStore` first, then advances `index`. If the save fails, it emits an error and reverts the card (NFR-7).

### 6.6 State shape (Swipe screen)

```kotlin
data class SwipeUiState(
    val current: CardState?,        // null when the deck is exhausted
    val next: CardState?,
    val progress: Progress,         // reviewed, total
    val queueCount: Int,
    val canUndo: Boolean,
    val muted: Boolean,
    val toast: ToastMessage?,
    val finished: SessionSummary?,
)
sealed interface SwipeIntent { Keep; Delete; Skip; Undo; ToggleMute; ConfirmFavoriteDelete; DismissToast }
```

The drag offset and animation state stay inside the `SwipeCard` composable, as local UI state. The ViewModel only receives committed intents.

### 6.7 Deletion flow

```
Swipe left ──► DecisionStore.record(DELETE)  (queued, item still in library)
Review queue ──► "Delete N" ──► CommitDeletionsUseCase
     └─► MediaLibrary.delete(ids) ──► PHPhotoLibrary.performChanges { deleteAssets }
              ├─ success   ─► DecisionStore.markDeleted(ids) ─► Success screen
              ├─ cancelled ─► no-op (queue unchanged)
              └─ error     ─► toast, queue unchanged
```

`CommitDeletionsUseCase` is the only caller of `MediaLibrary.delete` (NFR-5).

### 6.8 iOS platform notes

- `Info.plist`: `NSPhotoLibraryUsageDescription` = "Swipe Cleaner shows your photos one at a time so you can keep or delete them."
- Register `PHPhotoLibraryChangeObserver`, and on a change refresh counts and prune deleted IDs.
- Run PhotoKit fetches on `Dispatchers.Default`. Deliver image results onto a coroutine Flow, and cancel the `PHImageRequestID` when the collector is cancelled.
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

**SQLDelight schema** (`Decision.sq`, `Session.sq`)

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
  position    INTEGER NOT NULL DEFAULT 0,
  started_at  INTEGER NOT NULL,
  ended_at    INTEGER
);

CREATE TABLE session_skip (
  session_id  TEXT NOT NULL,
  asset_id    TEXT NOT NULL,
  PRIMARY KEY (session_id, asset_id)
);
```

- Stats (reviewed, kept, deleted) are computed with `COUNT` queries; nothing is stored separately.
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
  - NFR-5: deleting with no in-app confirmation does nothing, and `CommitDeletionsUseCase` is the only caller of `MediaLibrary.delete`.
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
| A `commonMain` file that references `MediaLibrary` and calls `.delete(` on it, other than `CommitDeletionsUseCase.kt` | NFR-5 |
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
| R1 | Compose MP on iOS janks during a drag while images decode | Decode off the main thread, cap the bitmap size to the screen, profile with Instruments in M1. |
| R2 | Video interop (`UIKitView`) clips badly or ignores card rotation and gestures | Spike in M1. Fallback: show the poster frame while dragging and the live player only when the card is at rest. |
| R3 | No public API for asset file size (`PHAssetResource` "fileSize" is undocumented KVC) | Storage estimate is optional (open question). Never block a feature on it. |
| R4 | The 7-day free signing expiry interrupts use | Documented re-sign step. Switch to a paid account if this gets annoying. |
| R5 | Accidental mass deletion | Batch confirm in the app plus the iOS dialog, Recently Deleted for 30 days, and a single delete code path (NFR-5). |
| R6 | iCloud-only items load slowly | Show the degraded image and a cloud badge, prefetch further ahead, and offer a setting to exclude them. |
