# Implementation progress (working notes)

Living scratchpad for the main agent while implementing plan.md. Updated at each merge.
Not a spec — `plan.md` and `REQUIREMENTS.md` are the source of truth.

## Environment notes

- **No vision**: this model has no image input. Sub-agents must NOT screenshot or read images.
- Icons: 15 Material Symbols Rounded vectors pre-converted into wp-1-2 worktree composeResources
  (source: marella/material-symbols svg/400/rounded, viewBox 0 -960 960 960 → 24x24 affine
  x/40, (y+960)/40; converter at /private/var/folders/hq/.../opencode/svg2vector.py).
- `xcode-select` points at CommandLineTools. Everything xcode/simctl/gradle needs
  `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` (validate.sh does this itself).
- JDK 21 active (temurin 21.0.12.1). Simulators available: iPhone 17 (2BE890EF...), iPhone 17 Pro, etc. iOS 26.5.
- Baseline `scripts/validate.sh` on main: PASSED (2026-11-01).
- Worktrees live in `/Users/lourw/personal/swipe-app-wt/wp-<id>` (outside repo). Remove each after merge:
  `git worktree remove ../swipe-app-wt/wp-<id>`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Workflow per WP (from AGENTS.md + plan.md §0)

0. Before dispatching: `ps aux | grep opencode` and check worktree mtimes — on 2026-11-01 a stale
   duplicate session was found editing wp-1-2 concurrently (killed with user approval). Never
   dispatch into a worktree another live session may hold.
1. `git worktree add ../swipe-app-wt/wp-N -b <branch> main` (after deps merged).
2. Dispatch implementer subagent (brief: read AGENTS.md + plan §0 + the WP + cited REQUIREMENTS §s; work only in that worktree; DEVELOPER_DIR export; validate; commit per §0; report §0.9; do NOT merge).
3. Main agent reviews diff; verification subagent for high-risk WPs (2.x gestures, 3.x delete path).
4. Merge `--ff-only` into main (rebase first if main moved), remove worktree, tick tracker here + plan.md.

## Wave plan

Subagents run strictly ONE AT A TIME (AGENTS.md). Serial queue order: 1.2 → 1.3 → 1.6 → 1.4 → 1.5 → 1.7 → 1.8 → ...

| Wave | WPs | Status |
| --- | --- | --- |
| A | 1.1 domain+fakes · 1.2 theme+components | 1.1 MERGED (faaccff) · 1.2 MERGED (c3088cb) |
| B | 1.3 SQLDelight · 1.4 PhotoKit read · 1.5 image loader · 1.6 deck use cases | ALL MERGED: 1.3 (3fdac74) · 1.6 (6581385) · 1.4 (b6be2e9) · 1.5 (9fecd2d) |
| C | 1.7 nav+onboarding (1.1,1.2,1.4) · 1.8 SwipeVM core (1.3,1.6) | BOTH MERGED: 1.7 (6d7f466) · 1.8 (3bebbf5) |
| D | 1.9 M1 deck screen (1.5,1.7,1.8) | 1.9 MERGED (8b17605) — **M1 code-complete**, 👤 checkpoints open |
| E | 2.1 SwipeCard gestures (1.2) · 2.2 SwipeVM full (1.8) | BOTH MERGED: 2.1 (e59a7cc) · 2.2 (6b5c800) |
| F | 2.3 Swipe screen assembly (2.1,2.2) | MERGED (8173e25) — **M2 code-complete** |
| G | 3.1 commit-deletions (1.3) · 3.2 PhotoKit delete (1.4) · 4.1 VideoPlayer (1.9) · 5.1 PhotoKit sources (1.4,1.6) | ALL MERGED (7d07bad · b239d2d · 21fcfda · beabe1a) — Wave G complete |
| H | 3.3 Queue UI (3.1,3.2,2.3) · 4.2 Video UX (4.1,2.3) · 5.2 HomeVM (5.1,1.3) | ALL MERGED (dfc92e2 · 07c9c3d · c5c2faa) — **M3 + M4 code-complete, Wave H complete** |
| I | 5.3 Home screens (5.2,1.7) · 6.1 Settings (5.3) — 6.1 after 5.3 | BOTH MERGED (9698ee5 · d873f01) — **M5 code-complete, Wave I complete** |
| J | 6.2 Accessibility (6.1) | MERGED (5fbc471) — 6.3 next |
| K | 6.3 Perf pass (6.2) · 6.4 Release sweep (6.3) | pending |

## WP status log

(append one line per WP when merged: branch, merge commit, deviations, open 👤 checkpoints)

### WP-6.2 (m6-accessibility) — verified APPROVE-WITH-MINOR, all 3 minors + 2 nits fixed, merged 5fbc471
- Swipe card: `contentDescription` (date→type→duration→favorite, pure
  `cardAccessibilityDescription`) + custom a11y actions Keep/Delete/Skip(/Undo-while-pending)
  running the SAME `onButtonCommit` path as the ActionBar; pinned screen-level (fire Delete →
  flight → `SwipeIntent.Delete`). `SystemActions.isReduceMotionEnabled()` (pre-existing WP-2.x
  plumbing) now drives `SwipeCard(reduceMotion=…)` everywhere + crossfade-not-slide nav via new
  `NavTransitions.kt`. `SemanticsAudit.assertAllClickablesLabeled()` helper + 10 per-screen
  NFR-8 tests (real screens/VMs; vacuous-fixture guard). Scrubber label, play affordance,
  badges, Months headings, Banner 44 dp floor fixed. Dynamic Type noted out-of-scope.
- Review fixes (5fbc471): audit now rejects BLANK labels (mutation: `label = ""` fails the
  Albums audit — killed); wired-action tests; format pinned; NFR-8 row names favorite flag;
  plan example updated to the built date-then-type shape (spec edited in-branch — pre-existing
  §5.7 wireframe `Mar 14, 2021 · 0:32` shape won over plan's type-first example; verifier
  ruled ACCEPTED). 319 tests, 0 fail; `--full` passed twice.
- Carried into later briefs:
  - WP-6.3: signposts must not regress card commit paths; per-settle AVPlayer churn and the
    7-enumeration Home refresh cycle (from WP-5.2/5.3 carry-forwards) are the top trace targets.
  - 👤: VoiceOver session on device (card actions audible, crossfade under Reduce Motion toggle,
    Accessibility Inspector reach on all 10 screens); CMP semantics→UIAccessibility bridge
    fidelity for `customActions` unverifiable without a device.
- Deferred nits: none blocking; `DebugSwipeCardDemo` intentionally unwired (commented).

### WP-6.1 (m6-settings) — verified APPROVE (2 nits: 1 fixed pre-merge, 1 recorded), merged d873f01 — **Wave I complete**
- Real `SettingsScreen` (placeholder replaced): 3 `ToggleRow`s over a new token-built
  `ui/components/Switch.kt` (haptics / includeCloudOnly / autoplayVideos, live-mirrored from
  `SettingsStore` StateFlows), Reset-all → `ConfirmDialog` → `DecisionStore.clearKeeps()`
  (FR-30; destructive pill styling like the queue dialog), version `InfoRow`, FR-31 expiry row.
  `SettingsViewModel` gates `clearKeeps` behind the dialog flag (airtight; orphan-confirm tested).
  FR-31: pure `ProvisioningProfile.parse(raw)` (`<?xml`…`</plist>` slice, realistic CMS-DER
  fixture) + `SystemActions.signingExpiry()` bundle read; any failure → row hidden (simulator
  hides, tested). Cloud-only: chose plan-sanctioned **load-time blocking** — every PhotoKit
  request path goes through `includeCloudOnly`-gated options (image, prefetch, video preload);
  deck membership toggle-independent; §6.5 documents it (rationale wording fixed pre-merge).
  `docs/manual-test.md` Settings section filled. 302 tests, 0 fail; `--full` passed; mutations
  (clearKeeps-wipes-DELETE, parser-accepts-garbage) each killed by exactly one test.
- Recorded, not changed: `signingExpiry()` does ~tens-of-KB bundle file I/O synchronously on
  main at VM construction (once per Settings entry; harmless).
- Carried into later briefs:
  - WP-6.2: the new `Switch` component + `ToggleRow`/`InfoRow` need the a11y pass like every
    other control (semantics for role + state, 44 dp targets); Settings screen joins the
    per-screen contentDescription audit list.
  - WP-6.3: no new hot paths (Settings reads are one-shot); verify `signingExpiry()` stays
    invisible in traces.
  - WP-6.4: §6.5 cloud-only wording is now precise; R6 mitigation = load-blocking + badge.
- 👤: on device, expiry row must show the free-provisioning 7-day date; with the iCloud toggle
  off, an iCloud-only item must show degraded/error placeholder, never spin (manual-test step 4).

### WP-5.3 (m5-home-screens) — verified APPROVE-WITH-MINOR, findings 1–3 fixed, merged 9698ee5 — **M5 code-complete**
- Home per §5.8: header + gear, stats line (FR-10 all three numbers — see spec edit), queue +
  limited banners (limited first, pinned by test), All/Photos/Videos `SegmentedControl`,
  Shuffle/Albums/By month rows, SMART FILTERS caption + 5 rows from `smartRows` (titles via
  `smartFilterTitle`, order = `SmartFilter.entries`), em-dash placeholders. New `SourceRow`
  component (64 dp, 40 dp cover thumb via `MediaLoader.image` small-target LRU bypass, chevron).
  `AlbumsViewModel` + `MonthsViewModel` (lazy: `months(type)` only while open, year sections
  newest-first FR-7); `HomeDestinations` collects `HomeViewModel.events` + the two screen events
  into nav (routes carry the type filter name-encoded, like `Swipe`). `SmartFiltersScreen` dropped
  as redundant — §5.8 screen map updated in-branch (plan-sanctioned). Minimal
  `SettingsScreen` placeholder registered so the gear lands somewhere (WP-6.1 replaces it).
  KDoc "persists across launches" claim dropped from `SetTypeFilter`. 286 tests, 0 fail;
  `validate.sh --full` passed. Verifier mutation-checked: banner order + months year headers.
- Spec edits (call out): §5.8 stats wireframe → `1,204 reviewed · 892 kept · 312 deleted`
  (FR-10 text listed three numbers; wireframe shorthand omitted kept — wireframe now matches FR);
  §5.8 screen map lost the Smart-filters branch.
- Carried into later briefs:
  - WP-6.1: replace the `ui/screens/settings/SettingsScreen.kt` placeholder (header +
    "Settings are coming soon.", gear route already works); no `typeFilter` field exists in
    `SettingsStore` and Home no longer claims persistence — only add one if FR-29/§5.8 grows it.
  - WP-6.2: the new Home/Albums/Months screens have had NO a11y pass — SourceRow counts/chevron,
    banner announcements, segmented control selection semantics, `ScreenHeader` back button,
    Months year headers as section semantics.
  - WP-6.3: `AlbumsViewModel` re-fetches album counts per open; `MonthsViewModel` pays the full
    `months()` enumeration per open (by design, FR-7) — time it at 50k.
  - Spec tidy-up: §5.8 says 🎉 is "the one allowed emoji" but the queue-banner wireframe itself
    shows 🗑 — implementation follows the wireframe; reconcile wording someday.
- 👤 visual checkpoints (all text-verified only): Home vs §5.8 (spacing, toggle, row order);
  both banners stacked with limited on top; em-dash → tabular numbers without layout jitter;
  album cover sharpness at 40 dp on a real library; Months ordering feel on a large library.

### WP-5.2 (m5-home-viewmodel) — verified APPROVE-WITH-MINOR, findings 1–5 + nits fixed, merged c5c2faa — **Wave H complete**
- `LoadSourcesUseCase(type)` progressive ladder: `albums()` estimates → ids-only Shuffle →
  `smartCounts(type)` (Bursts/Dupes full passes land last) → lazy FR-9 refine (Shuffle, smart rows
  one at a time, albums batched). Refine uses the deck's own path: Likely Duplicates subtracts
  decided **before** grouping (`duplicateCandidates` + `DuplicateGrouper`) — the old
  membership-minus-decided proved `refined 1 / deck 0` on mixed twin groups (fix test pins it).
  Album estimates are ALWAYS refined (`estimatedAssetCount` can report 0 for a non-empty album;
  new fake seam `nextAlbumsEstimate`). `SourceCounts` carries exactness flags;
  `HomeViewModel.exactRows` mask keeps refined numbers on screen across `changes()` re-runs — no
  exact→estimate→exact flicker. Filter switch clears every count (album rows lose the number, keep
  identity; FR-5 "never stale under a new label"). One reload collector: `MutableSharedFlow(replay=1)`
  + `flatMapLatest` (startup/filter/changes one path; back-to-back refresh requests coalesce).
  `HomeUiState`: `typeFilter`, `shuffleCount: Int?`, `smartRows` (always 5, §5.8 order),
  `albumRows: List<HomeAlbumRow>` (id/title/coverAssetId/count?), `stats`, `queueCount`, `access`.
  Events over `Channel(UNLIMITED)`: `OpenDeck(source, typeFilter)` / `OpenQueue` / `OpenSettings`
  (public `events` + `trySend`, QueueViewModel convention). §6.5 "Home counts" paragraph documents
  the ladder. 262 tests; new: duplicates-deck equality, zero-estimate refine, no-flicker refresh;
  FR-5 test now observes the placeholder clear (was mutation-survivable), months-never covers the
  filter reload. Mutation-checked: album-clear, mask, duplicates special-case each killed by exactly
  its test.
- Deviations: `HomeIntent.SetTypeFilter` KDoc claims "persists across launches" — store NOT wired
  yet; WP-5.3 must wire SettingsStore or drop the claim. `HomeUiState.albumRows` is a Home row
  model (nullable count), not `AlbumSource`.
- Carried into later briefs:
  - WP-5.3: render null counts as em dashes (NFR-4); banners — To-Delete iff `queueCount > 0`
    (tap → `OpenQueue`), limited-access iff `access == LIMITED` (FR-2, limited first; item count
    text owned by PermissionViewModel); wire `HomeViewModel.events` into the nav host with the
    CURRENT `typeFilter` from `OpenDeck`; cover thumbs via `HomeAlbumRow.coverAssetId` +
    `MediaLoader.image(id, 40dp×scale)` (small-target LRU bypass, §6.4); Months screen calls
    `library.months(type)` itself (Home state deliberately carries nothing); row titles/§5.8 order
    from `smartRows`; `HomeIntent.SetTypeFilter` persistence across launches must be wired (or the
    KDoc claim dropped).
  - WP-6.3: one refresh+refine cycle ≈ **7 whole-library enumerations** at 50k default Home
    (All-ids; Bursts + Dupes inside `smartCounts`; refine re-runs Bursts ids, Dupes candidates,
    Screenshots predicate, LongVideos duration sort — Bursts and Dupes pay 2 full passes each per
    cycle). Every `changes()` tick restarts the ladder (in-flight work lost). Reuse ideas: shuffle
    already holds its id list; streaming per-filter `smartCounts` could feed refine; album refine
    adds one collection fetch per album per cycle.

### WP-4.2 (m4-video-ux) — verified APPROVE-WITH-MINOR, findings fixed, merged 07c9c3d — **M4 code-complete**
- Tap matrix (chrome tap = mute on video cards / play-pause when autoplay-off started, session-level
  `muted` in SwipeUiState; photo cards keep chrome toggle), scrubber outside the card in the
  action-bar area (44dp touch band, caption+tnum, drag-to-seek via one-shot `SeekTarget` token,
  videos only), Live Photo press-and-hold overlay (`pressUpInteractionSource`, release/cancel
  stops), FR-18 badges (Live / cloud `isCloudOnly || LoadedImage.isCloud` / favorite) + `m:ss`.
  R2 policy: `TopCardPlayback` mounts the player only while `cardState.isIdle`; poster everything
  else. `VideoPlayerSlot` seam keeps Koin out of commonTest. `VideoPlayer` expect grew
  `seekTarget`/`seekPositionSeconds`; §6.4 in sync.
- Verifier: 4/4 mutations killed — including `isIdle`→`true` (R2 gate is load-bearing) and
  scrubber-over-card (the drag test does real hit-testing). Fixed pre-merge: the
  **restart-from-0-after-remount** deviation was documented nowhere → now in manual row 8 +
  `DeckVideoState` KDoc; scrubber inert while `durationMs <= 0` (unprepared seek would snap back);
  positional-args comment (named args impossible on function-type invocations).
- Carried into later briefs:
  - WP-6.2: scrubber has `progressBarRangeInfo` but no label; play affordance
    `contentDescription = null`; badges tag-only.
  - WP-6.3: per-settle AVPlayer create/release churn (inherent to R2 — measure); optional
    commit-release-frame assertion (structural mitigation exists: dragging=false + animating set
    synchronously before the coroutine).
  - WP-5.x/6.x: PhotoKit `isCloudOnly` still hardcoded null (WP-1.4 limitation) — cloud badge
    currently rides `LoadedImage.isCloud` only; wire the real signal.
- 👤 open (device): audible unmute, Live Photo hold feel + seeding, iCloud-only video/Live badge,
  smoothness while swiping a playing video — all in manual-test.md Video rows 9–12.

### WP-5.1 (m5-photokit-sources) — verified APPROVE-WITH-MINOR, findings fixed, merged beabe1a — Wave G complete
- **Simulator state note:** evidence was captured on a **wiped + re-seeded** iPhone 17 (13 assets,
  7 seeded fixtures) — the verifier confirmed the live Photos.sqlite matches; future evidence lines
  must state library state (finding).
- `sources()` split into `albums()` (cheap: estimatedAssetCount + firstObject cover, no per-asset
  enumeration) / `months(type)` (creationDate-only scan → pure `monthBuckets`, lazy, never at Home
  startup) / `smartCounts(type)`; `assetIds` real for every DeckSource; real `duplicateCandidates`
  (fetchKeys-limited, reuses DuplicateGrouper). Pure helpers in `SourceQueries.kt` shared by the
  fake (no drift); fake gained `monthsCalls` so WP-5.2 can assert Home never pays for the scan.
- SDK findings: no `Recents` smart-album subtype → mapped to `SmartAlbumRecentlyAdded`;
  `burstIdentifier` is not an NSPredicate keypath → `includeAllBurstAssets=true` + enumerate-filter
  (leak-checked: default deck unchanged, flag set only in burst paths); LONG_VIDEOS 60s inclusive
  in one shared constant; month membership local-zone, DST agreement proven (9.8M-sample property
  test) and now pinned by a committed Santiago 15-min-walk test.
- Verifier: mutations 3/3 killed at suite level; the named FR-7 test's tiebreak blind spot fixed
  (fixture reseeded January-before-March; re-checked, now killed directly). Fixed pre-merge:
  smartCounts KDoc/§6.4 honesty (**two** full-library passes: Bursts + Duplicates, placeholders
  first), burst count without list allocation, shared `compareDatesDescending`, NSDate ~100 ns
  boundary comment.
- Carried into later briefs:
  - WP-5.2: `smartCounts` off the main thread with placeholder counts first (two scans); album
    covers via `MediaLoader.image(coverAssetId, 40dp×scale)` (LRU-bypass by design); `months(type)`
    only when By-month opens (fake's `monthsCalls` asserts Home never calls it); `AlbumSource.count`
    is an estimate — FR-9 refine subtracts `decidedIds()` per §6.5.
  - WP-6.3: 👤 large-library timing for `smartCounts` + `albums` (NFR-4 budget) still open;
    Bursts/Selfies/screenshots-subtype are **fake-coverage-only** (addmedia cannot seed them —
    `ZISDETECTEDSCREENSHOT=0` confirmed) — manual-test.md must say device verification is required.
- 👤 open: large-library NFR-4 startup checkpoint.

### WP-4.1 (m4-video-player) — verified APPROVE-WITH-MINOR, findings fixed, merged 21fcfda
- First dispatch completed the code (7 commits) but was cancelled mid-evidence; a finisher
  session ran the simulator proof and stripped its driver. VideoPlayer expect/actual (the ONE
  sanctioned expect — check-rules.sh now guards it, incl. `internal expect` after this fix),
  iosMain VideoPlayerView (UIKitView + AVPlayerLayer sublayer; layerClass not overridable
  through K/N — documented deviation), VideoItemCache (deferred-as-entry, mutex, networkAccess
  per includeCloudOnly), VideoPreloader interface + FakeVideoPreloader, SwipeViewModel
  preloads the current+3 window alongside images (folded into refreshPrefetch for detekt).
- **Loop deviation:** AVPlayerLooper never queues a copy through K/N interop on iOS 26.5 →
  plain AVPlayer + end-of-item notification seek-to-zero; progress = currentTime modulo
  duration; §6.4 + expect KDoc updated to match. Live Photo = play once, no loop, paired
  resource → temp file.
- Verifier: 4/4 mutations killed (preload, cancel, duration guard, name escape); threading
  clean; evidence judged sound (5 loop wraps prove the notification fired). Fixed pre-merge:
  **requestPlayerItemForVideo DOES return a cancellable PHImageRequestID** (compile-probed) —
  now retained + cancelImageRequest on cancel (stops in-flight iCloud video downloads);
  Live Photo temp entries ref-counted so a re-mount racing a release keeps its file
  (WP-4.2 long-press prerequisite) + failed exports delete partial files; livePhotoTempFileName
  made actually injective (fixed-width hex escapes; old doubling collided on `a//b` vs `a_b`);
  expect KDoc onProgress-for-Live-Photos contradiction; stale looper comment; 9a1d1a3
  message amended to carry FR-16 (local-only rebase).
- Carried into later briefs:
  - WP-4.2: mount gated on SwipeCardState.isIdle (R2 — poster while dragging); autoplayVideos
    caller-side (poster + tap affordance); scrubber from onProgress (Live Photos DO fire
    progress — just show no scrubber there); the temp-file refcount fix landed, long-press
    re-mount is safe now; consider resume-at-end seek (pause racing the end notification);
    add 👤 cloud-only Live Photo check alongside the cloud-video one.
  - WP-6.3: VideoItemCache has no internal eviction (caller-discipline bound; KDoc notes the
    deferral) — add LRU/window-only eviction.
- 👤 open: Live Photo seeding + playback on device (addmedia can't seed Live Photos); R2
  visual confirmation at rest vs dragging (WP-4.2).

### WP-3.3 (m3-queue-ui) — verified APPROVE-WITH-MINOR, findings fixed, merged dfc92e2 — **M3 code-complete**
- QueueScreen (header "To delete (N)", hint, 3-col LazyVerticalGrid, pinned destructive CTA +
  Recently-Deleted caption, §5.8 empty state with "Back home"), GridTile (radius 8, 2 dp gap,
  duration bottom-right, NFR-8 desc date+type+duration), ConfirmDialog (NFR-5 gate),
  QueueItemViewer (full-screen Restore), DeleteSuccessScreen (display count, route carries the
  positive count), QueueUiState.tiles (metadata pages of 20, atomic with items — sequential
  collector makes a mismatch structurally impossible), PhotoKitMediaLoader LRU-bypass for
  small-target requests (tiles never evict deck decodes; per-tile request cancel). Placeholder
  screen deleted. 214 tests green; validate --full passed.
- Verifier: all four required tests non-vacuous (mutation-verified: GRID_COLUMNS 3→2 and
  dialog-bypass each fail exactly their test). NFR-5 gate structurally sound (single
  ConfirmDelete call site behind the dialog; VM guards; sole token site).
- Fixed post-review: "oldest first" checklist typo (FR-23 is newest-first); GridTile keep-ring
  was wired to nothing → now marks the tile open in the viewer; ConfirmDialog Cancel below 44 dp
  → minTouchTarget Box; DeleteSuccess KDoc contradicted the branch's own §6.7 zero-count rule →
  corrected; duration-position test renamed to what it asserts (no bounds API in this test
  artifact; position moved to the manual checklist).
- History: 9012933's missing Co-Authored-By amended via rebase (local-only branch; verified
  byte-identical trees before/after).
- Spec edits landed: §5.8 queue-empty CTA "Back home"; §6.7 zero-count commits stay quiet
  (haptic + nav suppressed, VM-tested); §6.4/§6.8 small-target LRU bypass + tile cancel.
- Carried into later briefs:
  - WP-5.3: Home "N waiting to delete" banner must ship WITH navigate(Queue) (verifier-
    confirmed not wired; TopBar badge + Summary CTA are).
  - WP-6.2: viewer-overlay back behavior if predictive back ever lands; VoiceOver announced
    order is date-then-type (doc example shows type-then-date — reconcile wording).
  - WP-6.3: QueueViewModel.tileMetadata refetches the whole queue per change (O(n²) under
    rapid restores) — cache id→MediaItem; consider bounded thumbnail cache.
- 👤 open (device, manual-test.md Delete section): iOS dialog confirm/cancel, Recently Deleted
  check, notificationSuccess haptic, VoiceOver read-out, scroll smoothness.

### WP-3.2 (m3-photokit-delete) — verified APPROVE-WITH-MINOR, findings fixed, merged b239d2d
- PhotoKitMediaLibrary.delete(): fetch by localIdentifier; zero fetched → Deleted WITHOUT
  performChanges (retry-convergence path); one performChanges{deleteAssets} bridged with
  suspendCancellableCoroutine; NSError mapping extracted to pure commonMain PhotoKitMapping
  (PHPhotosErrorDomain/3072 → Cancelled, else Failed) — covered by `FR-27 the iOS cancel error
  code maps to Cancelled`. changes() = per-collect conflated callbackFlow with a
  PHPhotoLibraryChangeObserver NSObject, unregistered in awaitClose. Wired: QueueViewModel
  re-prunes on changes() (skips while deleting) and PermissionViewModel refreshes the FR-2
  banner. performChanges/deleteAssets only in PhotoKitMediaLibrary.kt (check-rules + verifier
  grep confirm). Simulator-proven via a temporary probe (removed): Success(1) → asset
  unfetchable → row DELETED → re-delete dialog-free → live changes() ticks.
- Verifier caught TWO VACUOUS tests (mutation/probe-proven) — fixed and re-mutation-checked:
  the during-commit skip test now gates the fake inside delete (disabling the guard fails
  exactly it); the failing-change-prune test arms the failure after the load prune. F3:
  PermissionViewModel now awaits checkAccess in the collect body (conflation throttles bursts
  instead of stacking full library scans). F4/F5 doc nits landed.
- 👤 open: iOS system dialog confirm/cancel on device + Recently Deleted check (goes into the
  WP-3.3 manual checklist); during the probe the dialog was accepted by a real click at 13:32
  — dialog behavior itself not yet verified systematically.
- Carry-forward: changes() consumers must be idempotent re-read style (PhotoKit can tick while
  its own delete dialog is up); the deleting-guard race is benign (worst case a vanished row
  goes via prune instead of markDeleted).

### WP-3.1 (m3-commit-deletions) — verified APPROVE-WITH-MINOR (delete path, max scrutiny), findings fixed, merged 7d07bad
- CommitDeletionsUseCase + InAppDeleteConfirmation (internal ctor; sole construction site =
  QueueViewModel.ConfirmDelete; intersection before the ONE delete call; Cancelled/Failed →
  zero store calls) + PruneQueueUseCase (pages of 20, silent, session "prune") +
  RestoreFromQueueUseCase (KEEP under "restore"; no schema change — session_id is plain TEXT).
  QueueViewModel: one StateFlow + sealed intents + Channel events; init prunes then collects
  deleteQueue() live; double-confirm + empty-queue guards; notificationSuccess in the VM
  (per plan task 4, not a deviation). 29 tests incl. all three §8.2 safety tests (verifier:
  all real, pin store state). §6.7/NFR-5 token paragraph landed.
- Verifier found + we fixed: §6.7/KDoc overclaimed "exactly as the user saw it" (token is the
  live queue at tap time — reworded to what the code guarantees); restore() had no membership
  guard (now mirrors open(), tested); dangling test KDoc ref → check-rules guard #5; §7 now
  documents synthetic "restore"/"prune" session ids.
- Carried into later briefs:
  - WP-3.2: confirm the markDeleted-failure retry converges end-to-end (verifier finding 5:
    PhotoKit succeeded but store write failed → rows still queued → retry re-deletes vanished
    ids; the planned "zero assets → Deleted without a call" makes it converge); wire
    changes() → re-prune.
  - WP-3.3: `Deleted(0)` presentation (currently success haptic + "Deleted 0" screen — render
    neutral/skip when 0, suppress haptic; add VM test). §5.8 wireframe says "Tap an item to
    restore it" but FR-24 + VM = tap → full-screen → Restore; render per FR-24 and reconcile
    the wireframe caption.
  - WP-6.x: sessionCounts excludes DELETED rows → resumed `reviewedBase` under-reports after a
    mid-session commit (also logged under WP-2.2). DELETED rows keep session_id, so
    per-session attribution is possible later.

### WP-2.3 (m2-swipe-screen) — verified APPROVE-WITH-MINOR, findings fixed, merged 8173e25 — **M2 code-complete**
- SwipeScreen assembly: TopBar (progress `128 / 2,340` tnum+grouping, queue badge, close),
  ActionBar (undo/delete/skip, tinted), CardMetadata (96 dp scrim, date, duration), EmptyState,
  SummaryScreen (counts + queue-CTA iff N>0), Queue placeholder route, DeckMotion extracted
  (detekt), IosHaptics (UIKit generators, gated on SettingsStore.haptics, Koin-bound),
  docs/manual-test.md (every §5.6 row). SwipeCardState.flyOff takes reduceMotion (WP-2.1 carry).
  Events collected from first composition; 146 tests green; validate --full passed.
- Implementer invented FR-33 (spec ends FR-31) → renamed test to `5-8`, fixed FR-17→FR-20 and
  §682→§8.4 citations (commit messages of two earlier commits still say FR-33; history not rewritten).
- Spec edit (landed): §5.7 Toast — confirm-style toast (FR-22) does NOT auto-dismiss; wired via
  NEVER_AUTO_DISMISS. Fixing a real UX flaw the verifier caught (3 s fuse cancelled the
  confirmation window). Also: Reduce Motion callbackFlow was rebuilt per recomposition
  (observer churn) — now remember()'d.
- Deviations accepted: chrome components under `ui/screens/swipe/` (+ DeckMotion.kt) instead of
  plan's `ui/components/` + `ui/screens/summary/` — screen-scoped, single consumer.
- Carried into later briefs:
  - WP-3.3: replace QueuePlaceholderScreen; `notificationSuccess` haptic after batch delete;
    fill Delete section of manual-test.md.
  - WP-4.2: supply `isCloudOnly` to CardMetadata (Live/favorite badges already render);
    Live-Photo long-press (explicit no-op now); video scrubber in action-bar area; fill Video section.
  - WP-6.1: Settings stub; haptics toggle already silences IosHaptics, no extra wiring.
  - WP-6.2 polish queue: haptic fires on favorite-refused Delete (verdict haptic before VM
    refusal); CardMotionEffects new-card-vs-ReturnCard ordering race (one-frame hiccup,
    acknowledged in DeckMotion comment).
- 👤 open (device): haptics feel + toggle-off; real finger drags vs §5.6 numbers; Reduce Motion
  behavior; §5.8 visual QA (Swipe/Summary/Empty) — implementer had no vision, textual evidence only.

### WP-2.2 (m2-swipeviewmodel-full) — verified APPROVE-WITH-MINOR, finding 1 fixed, merged 6b5c800
- Undo (FR-15): in-memory ArrayDeque cap 50, remove-before-retreat, single-flight, `ReturnCard`
  event; FR-22 favorite confirm bound to its card (pendingFavoriteDelete, sync clear on accept);
  FR-16 mute; FR-21 sessionCounts (SQL, session-scoped) + finish() → SessionSummary + end();
  FR-20 resume = same seed rebuild-and-subtract (§6.5: `position` is a write-only hint),
  source/filter mismatch → fresh session. Events = UNLIMITED Channel (§6.6 policy; revertToken
  is the sole state-field exception). Suite split: core / UndoTest / SessionTest + Fixtures.
  WP-1.8 stale-comment carry-over resolved. Branch name deviated (missing hyphen) — cosmetic.
- Spec edits (landed): §6.4 `remove(id, sessionId)` + `sessionCounts`; §6.5 shuffle-before-subtract,
  resume semantics; §6.6 events policy + ToastMessage variants + pendingFavoriteDelete; FR-15
  "stack is in-memory only" clause.
- Follow-up queue (verifier notes, not blockers):
  - Undo/decide cross-kind race (sub-write window): undo can rewind under a completing decide →
    decided card re-shown (data durable, upsert-safe). Needs epoch/decide-in-flight guard,
    mirrors WP-1.8 double-tap fix. Small WP.
  - Resume `reviewedBase` from sessionCounts undercounts rows flipped DELETED mid-session →
    **WP-3.1 must know** (commit flow interacts).
  - Fresh empty deck never calls finish() → session row stays open, no summary. Manual-checklist item.
  - `ConfirmFavoriteDelete` defensive branch: clear pendingFavoriteDelete on rejection (unreachable today).
- Carried into WP-2.3 (brief must include):
  - Collect `viewModel.events` from first composition (LaunchedEffect); UNLIMITED buffer replays
    to a late collector → stale return animation.
  - `revertToken` still live (failed-save revert); `ReturnCard` covers undo + favorite-refusal returns.
  - `ToastMessage.FavoriteConfirm` → warning-style toast, "Delete" action → ConfirmFavoriteDelete,
    timeout → DismissToast; "Undone" + "Couldn't save — try again" toasts.
  - Undo is a no-op once summary shows (canUndo=false, stack cleared at finish()); Summary is terminal.

### WP-2.1 (m2-swipe-gestures) — first review REJECT (2 blockers), fixed; 2nd APPROVE-WITH-MINOR, fixed; merged e59a7cc
- SwipeSpec.kt (every §5.6 number, only place — check-rules clean), SwipeMath pure fns
  (rotation = dx/cardWidth × 12° clamped per literal spec; `>=` boundaries; flyOffTarget along
  release vector, 1.5-card off-screen), SwipeCard + SwipeCardState (gesture drag via
  pointerInput/VelocityTracker/Animatable; public `suspend fun flyOff(verdict)` = 80 ms/30 dp
  nudge → 220 ms fly-off → commit; returnFrom 260 ms; snapBack spring; re-entry guards),
  VerdictStamp overlay (KEEP TL / DELETE TR / SKIP BC, linear alpha, reduce-motion-safe),
  EdgeGlow (max 0.30). Gallery section (DebugSwipeCardDemo) with committing buttons.
- Test hooks: internal `RotationZSemanticsPropertyKey` + `stampTestTag()` (stamps keep
  clearAndSetSemantics + bare tag → no VoiceOver noise). All plan-exact test names byte-checked.
- Accepted relaxations: `config/detekt/detekt.yml` compose-rules ParameterNaming allows
  `onThresholdCrossed` (name fixed by plan; narrow, commented; verified real option).
- Carried into later briefs:
  - WP-2.3/6.2: `SwipeCardState.flyOff` hard-codes reduceMotion=false → button press under
    Reduce Motion flies off instead of crossfading; plumb the setting (plan signature gap, flag §).
  - WP-2.3: SwipeCard shadow hard-coded (blur 24 / 40%) — render underneath card as plain Box.
    onThresholdCrossed fires on zone entry (either swipe direction), documented.
- 👤: device 120 Hz drag feel (Instruments Animation Hitches); gallery visual QA vs §5.7
  (launch `dev.swipe.cleaner -gallery`, section 8).

### WP-1.9 (m1-deck-screen) — verified APPROVE-WITH-MINOR, findings 1–5 fixed, merged 8b17605 — **M1 code-complete**
- SwipeScreen (top card + next peek, Keep/Delete/Skip CircleIconButtons, empty state),
  `rememberMediaImage(id, targetPx)` (produceState keyed on id+targetPx+loader → one collector
  per key, cancel on dispose), `MediaLoader.FULL_TARGET_PX` shared by cards+prefetch (§6.4
  spec'd), Home "Start shuffle" → Swipe route (typeFilter route arg is String, unknown→ALL;
  navigation 2.10 can't do enum NavTypes). Real DI bug fixed: `single { Clock.System }`
  registered the companion subtype → `single<Clock>`; DiSmokeTest (iosTest) resolves the graph
  incl. the params-ful SwipeViewModel definition. `onCleared()` cancels the warm prefetch window.
- Spikes: **R2** — AVPlayerLayer in UIKitView ignores graphicsLayer rotation/translation
  (clips fine, stays axis-aligned) → **fallback needed**: poster while dragging, live player at
  rest (WP-4.1). Recorded in §10; throwaway branch `spike-video-interop` kept for 👤 visual
  confirmation. **R1** — 119-asset sim library, 60 concurrent full-size decodes, 0 on main;
  asset listing 50 ms. FR-20/FR-9 kill/relaunch persistence verified textually (8 decisions,
  queue=3 read back). 98 tests green; `validate.sh --full` passed.
- Carried into later briefs:
  - WP-4.1: R2 fallback design; confirm spike visually first (run `spike-video-interop`).
  - WP-2.2: full ToastMessage events policy; `Clock` constructor arg; `start()` error handling.
  - Note: prefetch warms PhotoKit's internal cache, not the app LRU (LRU fills on first card
    decode from the warm cache).
  - Note: duplicated "Stale-advance guard" comment block in SwipeViewModel.decide() (from
    3bebbf5) — one-line cleanup PR later.
- Open 👤: seeded-simulator walkthrough (launch → Home → Shuffle shows real photos; buttons
  record; kill/relaunch keeps decisions); §5.8/§5.7 visual QA of deck screen; device NFR-1
  (degraded < 100 ms under drag, Instruments); R2 visual confirmation.

### WP-1.8 (m1-swipe-viewmodel-core) — verified APPROVE-WITH-MINOR, finding 1 fixed, merged 3bebbf5
- SwipeViewModel core (§6.5): session+seed on init, BuildDeckUseCase, metadata pages of 20
  (refetch trigger index+5 ≥ loaded), record-before-advance with SaveFailed toast + revertToken
  on failure (CancellationException rethrown), prefetch current+3 with cancel for departed ids,
  queueCount live from deleteQueue(), exhausted → current=null. SwipeUiState/Intent/CardState.
  Koin: 3 use cases as singles; VM = `viewModel { params -> }`; WP-1.9 resolves via
  `koinViewModel { parameters { parametersOf(source, typeFilter) } }`. Spec edit: §6.6 +
  `revertToken: Long` + ToastMessage note (verifier: accurate, minimal). 87 tests green.
- Review finding fixed (3bebbf5): double-tap race — two intents captured the same id and
  index advanced twice, silently skipping a card. Now the advance is discarded when the deck
  head moved during the write; regression test `NFR-7 rapid double tap does not skip an
  undecided card` verified red→green.
- Carried into later briefs:
  - WP-1.9: `PREFETCH_TARGET_PX = IntSize(MAX, MAX)` relies on the loader's screen cap (§6.4);
    move/parameterize to the real screen size when wiring the deck screen. Resolve the VM via
    the params module above.
  - WP-2.2: `Clock` constructor arg intentionally omitted (nothing timestamps yet; first use
    with summary/session). `start()` has no error handling — session-create/buildDeck throw =
    dead launch; add toast+retry or 👤 decision. Full ToastMessage events policy due there.
  - Note: no `FR-20`-prefixed test; FR-20 logic covered inside FR-12/NFR-7 tests
    (createCalls seed, updatePositionCalls).
- No 👤 checkpoints (no device-dependent behavior yet beyond what WP-1.7/1.9 cover).

### WP-1.7 (m1-onboarding-permission) — verified APPROVE-WITH-MINOR, findings 1–2 fixed, merged 6d7f466
- Routes.kt (11 `@Serializable` routes; Onboarding/Denied/Home registered, rest are stubs),
  PermissionViewModel + UiState/Intent (DENIED always blocks; NOT_DETERMINED → Onboarding;
  RequestAccess → route by outcome; ON_RESUME Recheck), Onboarding/Denied screens,
  LimitedAccessBanner, placeholder HomeScreen ("Start shuffle" no-op), type-safe NavHost in
  App.kt, `-gallery` launch arg, FakeMediaLibrary `access` settable + one-shot
  `nextRequestAuthorizationResult`. 79 tests green; `validate.sh --full` passed on branch.
- Review finding fixed (6d7f466): NavHost graph was rebuilt on every state change
  (navigation-compose 2.10 resets back stack; popUpTo resolved against new graph).
  Now `startDestination` = first non-null route, transitions are imperative
  `navigate(...) { popUpTo(initialRoute) { inclusive = true } }`, FR-2 count collected
  inside `HomeContainer`. Test `FR-1 back cannot return to onboarding once home is reached`
  pins the guarantee via `popBackStack() == false`.
- Carried into later briefs:
  - WP-1.9: keep the NavHost builder lambda capture-free (collect flows inside destination
    composables); wire "Start shuffle" → `Swipe` route; transitions must stay imperative
    against the fixed `initialRoute`.
  - WP-3.2: FR-2 banner count refreshes on resume only; add `changes()`-driven refresh.
  - Note: AppSmokeTest uses `App(gallery = true)`; default `App()` + Koin path only exercised
    on real launch — a fakes-based smoke test is a good follow-up.
  - Note: FR-2 spec string ends "Manage."; rendered as Banner action label without the period
    (§5.7 pattern). Spec owner may want the literal — left as-is.
- Open 👤: reset-simulator walk (Allow Full → Home; Limit Access → banner w/ count;
  Don't Allow → Denied + Open Settings opens Settings); §5.8 screen-map + VoiceOver check;
  gallery screenshots via `xcrun simctl launch booted dev.swipe.cleaner -gallery`.

### WP-1.5 (m1-image-pipeline) — verified APPROVE-WITH-MINOR, findings fixed, merged 9fecd2d
- BitmapLruCache (mutex, K/N eviction-crash fix), PhotoKitMediaLoader (opportunistic/fast/
  aspectFit, decode on Default, cancel via awaitClose), LoadedImage + §6.4 updated,
  FakeMediaLoader degraded→full. Probe evidence: emission + cancel + cache-hit all logged.
- Carried into later briefs:
  - WP-1.9: one `image()` collector per (id,targetPx) key at a time (BitmapLruCache ownership
    contract); on-screen bitmap must stay MRU.
  - 👤: degraded→sharp visual sequence + NFR-1 timing on real/iCloud assets (WP-6.3).
  - Simulator "iPhone 17" TCC wedged for photos (grants don't reach PhotoKit); use
    `xcrun simctl erase`/reset or a fresh device for permission-sensitive checks.

### WP-1.4 (m1-photokit-library) — verified APPROVE-WITH-MINOR, findings fixed, merged b6be2e9
- PhotoKitMediaLibrary (read side), IosSystemActions, NSUserDefaultsSettingsStore, pure
  PhotoKitMapping in commonMain, Koin bindings. §6.8 string byte-exact via INFOPLIST_KEY_*
  build settings (no Info.plist edit needed — bundle plist verified). Portrait-only verified.
- Open 👤 (simulator tap): launch app, tap "Allow Access to All Photos", verify
  assetIds all=4 photos=3 videos=1 vs seeded fixtures (`/tmp/wp14-fixtures/`, re-seed with
  `xcrun simctl addmedia booted` if needed). LIMITED path needs a real "Select Photos…" tap.
- Open 👤 (device): `assetIds(All)` timing on a large library (NFR-4); Reduce Motion toggle;
  iCloud asset behavior.
- Note: `SystemActions.openSettings`/`presentLimitedLibraryPicker` are main-thread-only (KDoc).

### WP-1.6 (m1-deck-usecases) — verified APPROVE-WITH-MINOR, findings fixed, merged 6581385
- BuildDeckUseCase / RecordDecisionUseCase / UndoDecisionUseCase / DuplicateGrouper; §6.5
  documents pre-sorted LONG_VIDEOS + duplicate path. 2 s boundary (inclusive) and all-decided
  empty deck pinned by tests.
- Carried into later briefs:
  - WP-1.8: bind the three use cases in Koin (left unbound on purpose).
  - WP-1.4/5.1: real PhotoKit must honor the §6.5 contracts (duration-desc for LONG_VIDEOS,
    date-desc `duplicateCandidates`) that FakeMediaLibrary mirrors.

### WP-1.3 (m1-persistence) — verified APPROVE-WITH-MINOR, findings 1–3 fixed, merged 3fdac74
- Schema v1 + `1.db` snapshot, SqlDecisionStore/SqlSessionStore, DatabaseDriverFactory
  (Application Support, backup-excluded, corrupt file moved to `swipe-cleaner.db.corrupt`),
  Koin bindings. `iosApp` links `-lsqlite3`. DB materialises off main at Koin start (NFR-4).
- Carried into later briefs:
  - WP-1.8: `DecisionStore.remove(id)` clears skips from ALL sessions; §6.4 says "the row"
    (singular). Add a `session_id` param when the resume flow lands.
  - Spec-owner: `Stats.reviewed` = KEEP + pending DELETE + DELETED (SKIP excluded); the number
    moves on swipe-left before the iOS delete dialog confirms.
  - WAL sidecars (`-wal`/`-shm`) are not individually backup-excluded (Apple: main-file
    exclusion suffices).

### WP-1.2 (m1-components-base) — verified APPROVE-WITH-MINOR, all findings fixed, merged c3088cb
- Icons live in `commonMain/composeResources` (generated accessor is `Res`, not `R`).
- Deviations: Toast injectable-clock requirement met via `autoDismissDelay` param + Compose
  virtual time; PrimaryButton/QueueBadge "white" text = `textPrimary` (noted in §5.7).
- Carried into later briefs:
  - WP-6.2: several components set `contentDescription` duplicating readable child text
    (PrimaryButton/Toast/Banner/SegmentedControl) → possible double announcement in VoiceOver.
  - Debug gallery shows a non-spec `BannerTone.Info` variant next to the spec `Warning` default.
  - Gallery entry point: `App(gallery = true)` exists but no caller; whoever wires debug
    navigation (WP-1.3+) may also capture the 👤 gallery screenshots then.

### WP-1.1 (m1-domain-and-fakes) — verified APPROVE-WITH-MINOR, fixing + re-validating
- Findings 1–2 fixed in fakes directly (openSession/deleteQueue fixed-clock tie-breaks).
- Carried into later briefs:
  - WP-1.6: DuplicateGrouper must use the fake's run-chaining reading of FR-6 (each item ≤2 s
    of its predecessor, groups ≥2, adjacent; chaining allowed beyond 2 s total span).
  - WP-1.6/1.8: §6.4 `sources()` can't carry counts; Home rows use `assetIds(src).size`
    (or richer type later). FakeMediaLibrary.sources() returns [All] only — extend when needed.
  - WP-1.7+: FakeMediaLibrary.changes() has no replay — collect before notifying.
  - WP-1.7+: FakeSettingsStore flows are MutableStateFlow — direct writes bypass setCalls.

## Open 👤 human checkpoints

- WP-1.2: visual confirmation that FontFamily.Default renders as SF Pro (glyph-level);
  component gallery visual QA vs §5.7 wireframes.
