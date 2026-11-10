# Requirement coverage audit (WP-6.4)

Every FR and NFR from REQUIREMENTS.md, with the automated test(s) that pin it or
the doc that says why it is manual-only. Audit date: 2026-11-05.

- **Tests**: `commonTest` + `iosTest` (run with `./gradlew :composeApp:iosSimulatorArm64Test`).
  Cells list file + test-name prefix, not every test; most IDs have more.
- **Manual-only**: justified in [`manual-test.md`](manual-test.md) (device/UX checks),
  [`perf.md`](perf.md) (Instruments timing gates), or `scripts/check-rules.sh`
  (static guards REQUIREMENTS §8.3 accepts in place of tests).
- Headless Compose tests get a zero-sized window with zero insets, so pixel-level
  layout (safe areas, stamps, full-bleed rendering) is bounds-checked as far as
  the semantics tree allows and pixel-checked by 👤 in manual-test.md.

**Totals:** 31 FRs — 31 with automated tests; 0 test-less. 8 NFRs — 6 with
automated tests for their testable core, NFR-2 manual-only (Instruments),
NFR-6 guarded statically; NFR-1/3/4 timings are device gates in perf.md.

## Functional requirements

| ID | Requirement | Test(s) | Gap / manual note |
| -- | ------------- | ------- | ----------------- |
| FR-1 | First launch: Home + automatic `readWrite` request | `PermissionViewModelTest :: FR-1 first launch opens Home and auto-requests readWrite once`, `FR-1 no second request while one is in flight`, `FR-1 returning user is never prompted`, `PermissionFlowContentTest :: FR-1 home renders placeholders without fetching while access is undecided`, `FR-1 home loads its counts once access is granted`, `FR-1 back cannot leave Home on first launch`, `PhotoKitMappingTest :: FR-1 authorization status maps…` | Real system prompt: manual-test.md "Permissions" |
| FR-2 | Limited access → persistent banner + manage | `HomeScreenTest :: FR-2 limited banner…` (3), `PermissionViewModelTest :: FR-2…` (3), `PermissionFlowContentTest :: FR-2 flow shows home with the limited banner…` | `presentLimitedLibraryPicker` itself: manual-test.md "Permissions" |
| FR-3 | Denied → blocking screen + Open Settings | `DeniedScreenTest :: FR-3 denied screen explains and opens settings`, `PermissionViewModelTest :: FR-3…` (2), `PermissionFlowContentTest :: FR-3 flow routes denied…` | Settings app jump: manual-test.md "Permissions" |
| FR-4 | Home lists the four session sources | `HomeScreenTest :: FR-4 home lists all session sources`, `HomeViewModelTest :: FR-4 one changes emission refreshes counts exactly once`, `PermissionFlowContentTest :: FR-4 home shuffle navigates…` | — |
| FR-5 | All / Photos / Videos toggle over any source | `FakeMediaLibrarySourcesTest :: FR-5 type filter applies on top of every source`, `HomeScreenTest :: FR-5 segmented control…`, `LoadSourcesUseCaseTest :: FR-5…`, `MonthsViewModelTest :: FR-5…` | — |
| FR-6 | Smart filters: bursts, screenshots, long videos, duplicates | `DuplicateGrouperTest :: FR-6…` (4), `FakeMediaLibrarySourcesTest :: FR-6…` (4), `SourceQueriesTest :: FR-6 long video threshold is 60 seconds inclusive`, `BuildDeckUseCaseTest :: FR-6…` (2) | Real bursts/duplicates in a real library: manual-test.md setup |
| FR-7 | By month: year → month buckets, newest first | `SourceQueriesTest :: FR-7…` (6), `MonthsScreenTest :: FR-7…` (2), `MonthsViewModelTest :: FR-7…` (2), `FakeMediaLibrarySourcesTest :: FR-7…` (2) | — |
| FR-8 | Albums with covers and counts | `AlbumsScreenTest :: FR-8 album rows show cover and count`, `AlbumsViewModelTest :: FR-8…` (2), `FakeMediaLibrarySourcesTest :: FR-8 albums carry titles counts and newest cover` | — |
| FR-9 | Sources exclude Keep / pending Delete decisions | `BuildDeckUseCaseTest :: FR-9 deck excludes kept and queued items`, `SqlStoresTest :: FR-9 decidedIds…`, `LoadSourcesUseCaseTest :: FR-9…` (3), `HomeViewModelTest :: FR-9 source counts exclude decided items` | — |
| FR-10 | Home lifetime stats + queue size + review button | `HomeViewModelTest :: FR-10 home shows lifetime stats and queue size`, `HomeScreenTest :: FR-10 queue banner…` (2), `SqlStoresTest :: FR-10 stats counts reviewed kept deleted`, `SwipeScreenTest :: FR-10 queue badge…` | — |
| FR-11 | One item full-bleed, next rendered underneath | `SwipeScreenTest :: FR-11 the deck composes the current card with the next one underneath it`, `SwipeMathTest :: 5-6 under card scale tracks drag progress`, `SwipeScreenTest :: NFR-1 current and next cards collect their images at the full target` | Pixel-level bleed/stack look: manual-test.md "Layout and safe areas" + "Swipe" |
| FR-12 | Right swipe / Keep → KEEP + advance | `SwipeViewModelTest :: FR-12 keep records KEEP then advances`, `SwipeScreenTest :: FR-12 Keep button…`, `RecordDecisionUseCaseTest :: FR-12 record writes the verdict…` | Gesture itself: manual-test.md "Drag mechanics" |
| FR-13 | Left swipe / Delete → queued DELETE + advance | `SwipeViewModelTest :: FR-13 delete records DELETE then advances`, `SwipeScreenTest :: FR-13 Delete button…` | Gesture itself: manual-test.md |
| FR-14 | Up swipe / Skip → session-only SKIP | `SwipeViewModelTest :: FR-14 skip records SKIP for this session only`, `SwipeScreenTest :: FR-14 Skip button…`, `SqlStoresTest :: FR-14 skippedIds…` | Gesture itself: manual-test.md |
| FR-15 | Undo, 50-step stack, in-memory | `SwipeViewModelUndoTest :: FR-15…` (7, incl. 50-step cap), `UndoDecisionUseCaseTest :: FR-15…` (2), `SwipeScreenTest :: FR-15…` (3), `SqlStoresTest :: FR-15…` (2) | — |
| FR-16 | Video: autoplay muted/loop, tap mute, scrubber | `SwipeScreenVideoTest :: FR-16…` (4), `SwipeViewModelSessionTest :: FR-16 mute toggles…`, `VideoPlayerTest :: FR-16…` (5), `VideoScrubberTest :: FR-16…` (3) | Loop + autoplay on device: manual-test.md "Playback and mute (FR-16)" |
| FR-17 | Long-press Live Photo plays motion | `SwipeScreenVideoTest :: FR-17 long press on live photo starts playback`, `VideoItemCacheTest :: FR-17…` (3) | Motion actually playing: manual-test.md "Live Photos (FR-17)" |
| FR-18 | Card metadata: date, duration, badges | `SwipeScreenVideoTest :: FR-18 metadata shows date duration and badges`, `PhotoKitMappingTest :: FR-18 media type mapping recognises live photos` | Badge legibility: manual-test.md "Badges and duration (FR-18)" |
| FR-19 | Progress `reviewed / total` | `SwipeScreenTest :: FR-19 top bar shows reviewed over total with thousands grouping`, `SwipeViewModelTest :: FR-19 progress counts reviewed over total` | — |
| FR-20 | Decisions persisted immediately | `SqlStoresTest :: FR-20 recorded decision survives a new store instance` (+3), `SwipeViewModelTest :: NFR-7 decision is persisted before index changes` | Force-quit mid-session: manual-test.md "Decisions, toasts, and the session" |
| FR-21 | Session summary + queue CTA | `SummaryScreenTest :: FR-21…` (3), `SwipeViewModelSessionTest :: FR-21 exhausted deck emits session summary with counts`, `SqlStoresTest :: FR-21 sessionCounts…` | — |
| FR-22 | Favorite left-swipe → confirm toast | `SwipeViewModelSessionTest :: FR-22…` (4), `SwipeScreenTest :: FR-22 favorite confirm toast offers a Delete action` | — |
| FR-23 | Queue: 3-column grid, newest first | `QueueScreenTest :: FR-23 queue grid shows 3 columns`, `QueueViewModelTest :: FR-23…` (2), `SqlStoresTest :: FR-23…` (3) | — |
| FR-24 | Tile → full screen, Restore → KEEP | `QueueViewModelTest :: FR-24…` (5), `QueueScreenTest :: FR-24…` (3), `RestoreFromQueueUseCaseTest :: FR-24…` (2) | — |
| FR-25 | One `performChanges { deleteAssets }` for the queue | `CommitDeletionsUseCaseTest :: FR-25 commit calls MediaLibrary delete exactly once with every queued id`, `QueueScreenTest :: FR-25 dialog Delete commits…`, `QueueViewModelTest :: FR-25…` | iOS system dialog itself: manual-test.md "The double confirm (NFR-5, FR-25)" |
| FR-26 | Confirmed → DELETED + success screen | `CommitDeletionsUseCaseTest :: FR-26 confirmed delete marks items DELETED and empties the queue`, `DeleteSuccessScreenTest :: FR-26…` (2), `QueueScreenTest :: FR-26 success event navigates…`, `PhotoKitMappingTest :: FR-26…` | Assets actually gone in Photos: manual-test.md "The double confirm" |
| FR-27 | iOS cancel → queue unchanged, no error | `CommitDeletionsUseCaseTest :: FR-27 cancelling the iOS dialog leaves the queue exactly as it was`, `QueueViewModelTest :: FR-27…`, `PhotoKitMappingTest :: FR-27…` | Dialog cancel: manual-test.md "The double confirm" |
| FR-28 | Dead queued assets pruned silently on load | `PruneQueueUseCaseTest :: FR-28…` (2), `QueueViewModelTest :: FR-28…` (3) | Delete elsewhere then reopen: manual-test.md "Errors and edge cases (FR-28)" |
| FR-29 | Settings toggles (haptics, iCloud, autoplay) | `SettingsScreenTest :: FR-29 toggle rows render the state and toggle through intents`, `SettingsViewModelTest :: FR-29…` (2, incl. `includeCloudOnly` persisting) | The iCloud gate acts through PhotoKit's `isNetworkAccessAllowed` on the loader (no unit-testable seam; WP-6.1 design note in §6.5) — real iCloud-only asset check: manual-test.md "Settings" |
| FR-30 | Reset decisions, confirmation, queue untouched | `SettingsViewModelTest :: FR-30…` (3), `SettingsScreenTest :: FR-30…` (2), `SqlStoresTest :: FR-30 clearKeeps leaves the delete queue untouched` | — |
| FR-31 | Version + signing expiry row | `SettingsViewModelTest :: FR-31…` (2), `SettingsScreenTest :: FR-31…` (2), `ProvisioningProfileTest :: FR-31…` (3) | Expiry only shows for signed builds: manual-test.md "Settings" |

## Non-functional requirements

| ID | Requirement | Test(s) | Gap / manual note |
| -- | ------------- | ------- | ----------------- |
| NFR-1 | Next card ≤ 100 ms, full ≤ 500 ms | `SwipePerfTraceTest :: NFR-1…` (4, signpost span wiring), `SwipeScreenTest :: NFR-1 current and next cards collect their images at the full target` | The ms numbers are an Instruments gate on device: [`perf.md`](perf.md) (spans `NEXT_CARD_DEGRADED` / `NEXT_CARD_FULL`) |
| NFR-2 | Drag at refresh rate, no decode hitches | — | Manual-only by nature (frame timing on device): [`perf.md`](perf.md) "Animation Hitches" step; decode-off-main was programmatic in M1 (REQUIREMENTS §9 R1). 👤 carry-forward |
| NFR-3 | < 300 MB resident, ≤ 5 full-screen bitmaps | `BitmapLruCacheTest :: NFR-3 cache never holds more than 5 bitmaps` (+2), `SwipeScreenTest :: NFR-3 recomposition does not add a second image collector per card`, `GridTileTest :: NFR-3…`, `QueueScreenTest :: NFR-3…` | Resident-memory number: [`perf.md`](perf.md) "Allocations" step |
| NFR-4 | Cold start < 1.5 s, lazy fetches | `HomeViewModelTest :: NFR-4 home state is emitted before counts finish loading` + `NFR-4 home never loads months`, `LoadSourcesUseCaseTest :: NFR-4 smart counts arrive after the cheap rows…`, `HomeScreenTest :: NFR-4 loading counts render as em dash placeholders` | The 1.5 s number on a 50k library: [`perf.md`](perf.md) span `COLD_START_HOME` |
| NFR-5 | Single delete path + in-app confirmation | `ConfirmDialogTest :: NFR-5…` (3), `QueueViewModelTest :: NFR-5 deleting with no in-app confirmation does nothing`, `QueueScreenTest :: NFR-5 delete button opens in-app confirm and does not delete`, `DiSmokeTest` | `check-rules.sh` guards the single-path rule statically (checks 4–5) |
| NFR-6 | No network / analytics | — | Manual-only by nature: `check-rules.sh` check 6 fails the build on any networking or analytics import (REQUIREMENTS §8.3 static guard); no network code exists to unit-test |
| NFR-7 | Write before advance; revert on failure | `SwipeViewModelTest :: NFR-7 decision is persisted before index changes` + `NFR-7 failed write does not advance and reverts card`, `RecordDecisionUseCaseTest :: NFR-7 record rethrows store failure`, `SwipeScreenTest :: NFR-7 save-failed toast…` | — |
| NFR-8 | Buttons for every swipe action, VoiceOver, custom actions, Reduce Motion | `SwipeCardTest :: NFR-8 swipe card exposes keep delete skip accessibility actions`, `SwipeScreenTest :: NFR-8…` (4), `CardMetadataTest :: NFR-8…` (3), per-screen `NFR-8 every screen's clickable nodes…` audits, `SwipeCardTest :: 5-6 reduce motion commits without rotation` (Reduce Motion), `SwipeMathTest` (§5.6 math) | VoiceOver read-out quality: manual-test.md ("👤 for the full read-out") |

## Gaps and carry-forwards

- **NFR-2** is the only requirement with no automated test at all; it needs
  Instruments on a device (`perf.md`). WP-6.3's 👤 device perf pass remains
  open on `docs/perf.md` "Results".
- NFR-1 / NFR-3 / NFR-4 have their **mechanisms** tested (span wiring, LRU
  cap, lazy loading) but their **numbers** are device gates in `perf.md`.
- All 👤 device checks live in [`manual-test.md`](manual-test.md) and stay open
  until the WP-6.4 device run.
