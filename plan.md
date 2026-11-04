# Swipe Cleaner — Implementation Plan

_Created 2026-11-01. Source of truth for behavior: [`REQUIREMENTS.md`](REQUIREMENTS.md). Rules for agents: [`AGENTS.md`](AGENTS.md)._

This plan splits milestones M1–M6 (REQUIREMENTS §9) into **work packages (WPs)**. Each one is small enough for a single sub-agent to finish in one branch. Every WP lists:

- **Depends on**: WPs that must be merged into `main` first.
- **Branch**: the branch name to use.
- **Files**: what to create or change.
- **Tasks**: what exactly has to get done.
- **Tests**: test names to add. Each starts with its requirement ID (§8.2).
- **Done when**: the definition of complete for this WP. Every item has to be true.

Tick the checkbox next to a WP in the [tracker](#tracker) once it's merged.

---

## 0. Global definition of done (applies to every WP)

A WP is **complete** only when all of the following are true. The per-WP "Done when" lists are **in addition** to this.

1. **Branch hygiene.** The work is on its own branch, cut from an up-to-date `main`, and named as in the WP. There are small commits, each of which builds. Each commit message names the requirement IDs it covers, e.g. `FR-15 add undo stack to SwipeViewModel`, and ends with the attribution line.
2. **`scripts/validate.sh` passes** (ktlint → detekt → check-rules → compile → migrations → simulator tests). If the WP touches `iosApp/`, `iosMain/`, or any Gradle file, **`scripts/validate.sh --full`** passes too. (Docs-only changes skip validation, per AGENTS.md.)
3. **No suppressions.** No new lint baselines. No `@Suppress` without a comment explaining why. No `@Ignore`d tests.
4. **Hard rules hold** (AGENTS.md 1–8). In particular: no raw `dp`/`sp`/`Color(0x` outside `ui/theme/` and `SwipeSpec.kt`, no `platform.*` import in `commonMain`, and no new caller of `MediaLibrary.delete`.
5. **Tests.** Every use case and ViewModel change has a `commonTest` using the fakes in `commonTest/kotlin/dev/swipe/fakes/` (no mocking libraries), with Turbine for Flows, `runTest` virtual time, `FakeClock`, and fixed seeds. Every FR/NFR the WP implements that has logic behind it has at least one test whose name starts with its ID.
6. **Spec stays in sync.** If the WP changes an interface, a token, a gesture constant, or behavior described in REQUIREMENTS.md, the spec is updated **in the same branch**, and the WP's final summary calls the change out. (Several WPs below already flag expected spec edits.)
7. **Extra checks from REQUIREMENTS §8.4** for the kinds of files touched have been done. For example: screens get run on the simulator and compared to their §5.8 wireframe; `.sq` changes get a migration and a schema snapshot. Anything that needs a **physical device or Instruments** is listed as a 👤 *human checkpoint*. The sub-agent can't do it, so it writes down exactly what to check and leaves it open in the summary.
8. **Merged.** `git checkout main && git merge --ff-only <branch> && git branch -d <branch>`. If `main` has moved on, rebase, re-validate, then merge.
9. **Summary handed back.** The sub-agent reports: what was built, the test names added, any spec edits, any open 👤 checkpoints, and anything it deferred.

### Sub-agent brief template

When dispatching a WP, give the sub-agent:

> Implement **WP-X.Y** from `plan.md` in `/Users/lourw/personal/swipe-app`. Read `AGENTS.md`, the REQUIREMENTS sections the WP cites, and the WP itself before you start. Follow §0 "Global definition of done". Don't start other WPs. If the spec is ambiguous, use the defaults under REQUIREMENTS §2 "Open questions". If the spec and the code disagree, update the spec and say so. Report back using §0 item 9.

---

## 1. Dependency graph and waves

WPs in the same wave don't depend on each other and can run in parallel, each in its own worktree or branch.

```
Wave A  WP-1.1 Domain + interfaces + fakes      WP-1.2 Theme tokens + icons + base components
           │                                              │
Wave B  WP-1.3 SQLDelight store   WP-1.4 PhotoKit library (read)   WP-1.5 Image loader + LRU   WP-1.6 Deck use cases
           │                         │                                │                          │
Wave C  WP-1.7 Nav + onboarding/permission (needs 1.1, 1.2, 1.4)      │                          │
        WP-1.8 SwipeViewModel core (needs 1.3, 1.6) ──────────────────┤
Wave D  WP-1.9 M1 deck screen + spikes (needs 1.5, 1.7, 1.8)  ◄───────┘
           │
Wave E  WP-2.1 SwipeCard gestures   WP-2.2 SwipeViewModel full (undo, favorites, summary, session)
           │                          │
Wave F  WP-2.3 Swipe screen assembly + summary + haptics + manual-test.md
           │
Wave G  WP-3.1 CommitDeletions + QueueViewModel   WP-3.2 PhotoKit delete + change observer   WP-4.1 VideoPlayer actual   WP-5.1 PhotoKit sources
Wave H  WP-3.3 Queue UI + success                 WP-4.2 Video/Live UX in swipe              WP-5.2 Home VM + LoadSources
Wave I  WP-5.3 Home + source screens              WP-6.1 Settings
Wave J  WP-6.2 Accessibility + Reduce Motion
Wave K  WP-6.3 Performance pass (👤)              WP-6.4 Release-readiness sweep
```

---

## 2. Milestone M1 — Library access

### WP-1.1 Domain models, data interfaces, and test fakes
- **Depends on:** nothing (M0 is done).
- **Branch:** `m1-domain-and-fakes`
- **Spec:** §6.4, §6.6, §7, §8.2.

**Files**
- `commonMain/.../domain/model/`: `MediaItem.kt`, `MediaType.kt`, `Decision.kt`, `DeckSource.kt`, `SmartFilter.kt`, `MediaTypeFilter.kt`, `LibraryAccess.kt`, `DeleteResult.kt`, `Progress.kt`, `SessionSummary.kt`, `Stats.kt`.
- `commonMain/.../data/`: `MediaLibrary.kt`, `MediaLoader.kt`, `DecisionStore.kt`, `SessionStore.kt`, `SettingsStore.kt`, `Haptics.kt`, `SystemActions.kt`, `Clock.kt` (or use `kotlin.time.Clock`, and inject it).
- `commonTest/.../fakes/`: `FakeMediaLibrary.kt`, `FakeMediaLoader.kt`, `FakeDecisionStore.kt`, `FakeSessionStore.kt`, `FakeSettingsStore.kt`, `FakeHaptics.kt`, `FakeSystemActions.kt`, `FakeClock.kt`.

**Tasks**
1. Write the models exactly as in §7. `DeckSource` is a `@Serializable` sealed interface: `All`, `Album(id, title)`, `Month(year, month)`, `Smart(kind: SmartFilter)`. `SmartFilter` = `SCREENSHOTS, LONG_VIDEOS, BURSTS, LIKELY_DUPLICATES, SELFIES`. `MediaTypeFilter` = `ALL, PHOTOS, VIDEOS`. `DeleteResult` = `Deleted`, `Cancelled`, `Failed(message)`. `LibraryAccess` = `FULL, LIMITED, DENIED, NOT_DETERMINED`.
2. Write the interfaces from §6.4 as given, plus these additions. Add each one to §6.4 in REQUIREMENTS:
   - `DecisionStore.stats(): Flow<Stats>` (reviewed, kept, deleted, queued) for FR-10. §7 says stats come from `COUNT` queries, so this is a query, not stored state.
   - `DecisionStore.record` treats `SKIP` as a write to `session_skip`, never to `decision` (§7 comment). `remove(id)` removes the row from whichever table holds it.
   - `SessionStore`: `create(source, typeFilter, seed): String`, `updatePosition(id, position)`, `end(id)`, `openSession(): SessionRecord?`, `skippedIds(sessionId)`.
   - `SettingsStore`: `haptics`, `includeCloudOnly`, `autoplayVideos` as `StateFlow<Boolean>` + setters (defaults: `true`, `true`, `true`). Also `onboardingSeen`.
   - `Haptics`: `selection()`, `impactLight()`, `impactMedium()`, `notificationSuccess()`.
   - `SystemActions`: `openSettings()`, `presentLimitedLibraryPicker()`, `isReduceMotionEnabled(): Flow<Boolean>`, `appVersion(): String`, `signingExpiry(): Instant?`.
3. Write the fakes. Each fake: holds in-memory state, **records every call** (e.g. `val deleteCalls = mutableListOf<List<String>>()`), and has a failure switch (e.g. `var failNextRecord: Throwable?`, `var nextDeleteResult: DeleteResult`). `FakeMediaLibrary` is seeded with a `List<MediaItem>` and filters it for `assetIds(source, type)` in the same way PhotoKit would.
4. Add a `domainModule`/`dataModule` placeholder in `di/CommonModule.kt` only if something needs binding now. Otherwise leave it.

**Tests**
- `fakes/FakesSelfTest.kt`: `FakeDecisionStore records and fails on demand`, `FakeMediaLibrary filters by type`. These keep the fakes honest.
- `domain/model/DeckSourceTest.kt`: `DeckSource round-trips through JSON` (needed for `session.source`).

**Done when**
- [ ] All the types above exist and compile. KDoc on each interface method cites its FR/NFR.
- [ ] Every fake can be made to fail and exposes its recorded calls.
- [ ] REQUIREMENTS §6.4 lists the added methods and interfaces.
- [ ] §0 holds.

---

### WP-1.2 Theme completion, icons, and base components
- **Depends on:** nothing. Runs in parallel with WP-1.1.
- **Branch:** `m1-components-base`
- **Spec:** §5.3–5.5, §5.7.

**Files**
- `ui/theme/Dimens.kt`: add a `SwipeComponentSizes` token group, exposed as `SwipeTheme.componentSizes`.
- `composeResources/drawable/`: Material Symbols Rounded vector XMLs for `check`, `close`, `arrow_upward`, `undo`, `delete`, `settings`, `chevron_right`, `arrow_back`, `cloud`, `favorite`, `live` (motion_photos_on), `photo_library`, `shuffle`, `calendar_month`, `filter_list`. Get them from the Material Symbols repo (Apache-2.0). Add a `docs/third-party.md` note.
- `ui/components/`: `SwipeIcon.kt`, `PrimaryButton.kt`, `Banner.kt`, `Toast.kt`, `QueueBadge.kt`, `CircleIconButton.kt`, `SegmentedControl.kt`.

**Tasks**
1. Every component size from §5.7 is a token, because check-rules forbids raw `dp` outside `ui/theme/`: action button large 64, skip 48, undo 40, top bar 44, progress line 2, stamp stroke 3, card inset 8, card scrim 96, source row 64, row thumbnail 40, primary button 52, badge min 20, grid gap 2, tile ring 3, banner bar 4, card shadow blur 24 (alpha 0.40 in colors/elevation tokens), action-bar gap 24.
2. There's no Material dependency (the app uses `foundation` + `BasicText`). Keep it that way. Build components on `foundation` primitives. Icons load with `painterResource(Res.drawable.x)`.
3. `PrimaryButton(text, onClick, destructive = false, enabled)`: 52 dp pill, full width minus gutters, `accent` (or `delete`) fill, `headline` white text.
4. `Banner(text, actionLabel, onAction)`: `surfaceRaised`, 4 dp leading `warning` bar, `callout` text, `accent` action.
5. `Toast(message, actionLabel?, onAction?, onDismiss)`: radius 12, `surfaceRaised`, `callout`. It auto-dismisses after 3 s. The timeout is a named constant in the component, and the clock is injectable for tests.
6. `QueueBadge(count)`: `delete` pill, min 20 dp, `caption`, shows `99+` above 99, tabular figures.
7. `SegmentedControl(options, selected, onSelect)` for All/Photos/Videos.
8. Every interactive component sets `Modifier.semantics { contentDescription; role }` and has a touch target of at least 44 dp.
9. Confirm `FontFamily.Default` renders as SF Pro on the simulator (§5.4). Take a screenshot of a text sample and compare it against SF metrics. If it doesn't, bundle Inter and record the result in §5.4.

**Tests** (`commonTest`, `runComposeUiTest`)
- `QueueBadgeTest`: `5-7 badge shows 99+ above 99`, `5-7 badge shows exact count up to 99`.
- `ToastTest`: `5-7 toast auto-dismisses after 3 s` (virtual clock).
- `PrimaryButtonTest`: `NFR-8 primary button exposes its label to accessibility`.
- `ComponentSizesTest`: `5-7 component sizes match REQUIREMENTS`.

**Done when**
- [ ] Every size in §5.7 is reachable through `SwipeTheme`. `scripts/check-rules.sh` finds no raw `dp`/`sp` in `ui/components/`.
- [ ] The listed components exist, have previews or a simple gallery screen (debug only), and pass the tests above.
- [ ] Font result recorded in §5.4.
- [ ] 👤 Not needed. Simulator screenshots of each component are attached to the summary.
- [ ] §0 holds.

---

### WP-1.3 SQLDelight schema and stores
- **Depends on:** WP-1.1.
- **Branch:** `m1-persistence`
- **Spec:** §7, NFR-7, FR-9, FR-20, FR-23.

**Files**
- `commonMain/sqldelight/dev/swipe/db/Decision.sq`, `Session.sq`.
- `commonMain/sqldelight/databases/1.db`: the schema snapshot, generated and committed.
- `commonMain/.../data/db/SqlDecisionStore.kt`, `SqlSessionStore.kt`.
- `iosMain/.../platform/DatabaseDriverFactory.kt`: `NativeSqliteDriver`, file in Application Support, `NSURLIsExcludedFromBackupKey = true`.
- `iosTest/.../data/db/`: store tests using an in-memory native driver.
- `di/CommonModule.kt` and `di/IosModule.kt`: bindings.

**Tasks**
1. Write the schema from §7 exactly: `decision`, `decision_by_type` index, `session`, `session_skip`. This is schema version 1, so it has no `.sqm` yet. Run `generateCommonMainSwipeDatabaseSchema` (or the equivalent task) so `1.db` exists, and check that `verifySqlDelightMigration` passes.
2. Named queries: `upsert`, `deleteById`, `decidedIds` (`KEEP` + `DELETE`), `deleteQueue` (`DELETE` ordered by `decided_at DESC`, FR-23), `markDeleted`, `clearKeeps` (only `KEEP` rows, FR-30), `countByDecision`, plus the session queries.
3. `SqlDecisionStore` runs every query on `Dispatchers.Default` (inject the dispatcher). `deleteQueue()` and `stats()` use `asFlow().mapToList()` so the UI updates live. Writes propagate exceptions, so the caller can revert (NFR-7).
4. `record(id, SKIP, session)` inserts into `session_skip` only.

**Tests** (`iosSimulatorArm64Test`, in-memory driver)
- `FR-20 recorded decision survives a new store instance`
- `FR-9 decidedIds contains KEEP and DELETE but not SKIP or DELETED`
- `FR-23 deleteQueue is newest decision first`
- `FR-30 clearKeeps leaves the delete queue untouched`
- `FR-15 remove deletes a decision and a session skip`
- `FR-10 stats counts reviewed kept deleted`

**Done when**
- [ ] The schema matches §7 column for column. `1.db` is committed. `verifySqlDelightMigration` passes.
- [ ] Both stores are bound in Koin, and the app launches on the simulator with the DB file created under Application Support (check with `xcrun simctl get_app_container booted dev.swipe.cleaner data`).
- [ ] `scripts/validate.sh --full` passes, because this touches `iosMain`.
- [ ] §0 holds.

---

### WP-1.4 PhotoKit media library (read side)
- **Depends on:** WP-1.1.
- **Branch:** `m1-photokit-library`
- **Spec:** FR-1–3, §6.5 step 1, §6.8, NFR-4, NFR-6.

**Files**
- `iosMain/.../platform/PhotoKitMediaLibrary.kt`: `authorization`, `requestAuthorization`, `assetIds(All, type)`, `items(ids)`. Leave `delete` as `TODO`-free `DeleteResult.Failed("not implemented")` until WP-3.2, and `sources()` returning `[All]` until WP-5.1.
- `iosMain/.../platform/IosSystemActions.kt`: `openSettings` (`UIApplicationOpenSettingsURLString`), `presentLimitedLibraryPicker` (`PHPhotoLibrary.sharedPhotoLibrary().presentLimitedLibraryPickerFromViewController` on the top VC), `isReduceMotionEnabled` (`UIAccessibilityIsReduceMotionEnabled` + the `UIAccessibilityReduceMotionStatusDidChangeNotification` observer), `appVersion` (bundle `CFBundleShortVersionString` + `CFBundleVersion`). Keep `signingExpiry` returning `null` until WP-6.1.
- `iosMain/.../platform/NSUserDefaultsSettingsStore.kt`.
- `iosApp/Info.plist`: add `NSPhotoLibraryUsageDescription` with the exact §6.8 string, and set `UISupportedInterfaceOrientations` to portrait only.
- `di/IosModule.kt`: bindings.

**Tasks**
1. Map `PHAuthorizationStatus` to `LibraryAccess` (`authorized`→FULL, `limited`→LIMITED, `denied`/`restricted`→DENIED, `notDetermined`→NOT_DETERMINED). Request with `PHAccessLevelReadWrite`, bridging the callback to `suspendCancellableCoroutine`.
2. `assetIds`: `PHAsset.fetchAssetsWithOptions` with a predicate for the type filter (`mediaType == image/video`). Enumerate **only `localIdentifier`** into a `List<String>`. Don't touch any other property, so a 50k library stays fast. Run on `Dispatchers.Default`.
3. `items(ids)`: `fetchAssetsWithLocalIdentifiers` for at most 20 IDs at a time. Map to `MediaItem` (Live Photo = `mediaSubtypes & PHAssetMediaSubtypePhotoLive`, screenshot subtype, `burstIdentifier`, `favorite`, `pixelWidth/Height`, `duration` → ms). Set `isCloudOnly = null`. Keep the input order.
4. Keep the code thin (§8.2: no PhotoKit in automated tests). Put any non-trivial mapping logic into a pure `commonMain` function that can be tested, e.g. the bitmask → `MediaType` mapping takes plain ints.

**Tests**
- `commonTest`: `FR-18 media type mapping recognises live photos` on the extracted pure mapper.
- Manual (simulator, seeded with `docs/fixtures`): log `assetIds(All, ALL).size` and the first page of `items` from a temporary debug hook, then remove the hook before committing.

**Done when**
- [ ] On a fresh simulator, the permission prompt shows the §6.8 string, and all three outcomes (full / limited / denied) map correctly. Check this with `xcrun simctl privacy booted grant|revoke|reset photos dev.swipe.cleaner`.
- [ ] `assetIds` returns the right count on the seeded simulator for ALL / PHOTOS / VIDEOS.
- [ ] The app is portrait-only.
- [ ] `scripts/validate.sh --full` passes.
- [ ] 👤 On a device with a large real library, time `assetIds(All)` and record the result in the summary (target: comfortably inside the 1.5 s NFR-4 budget).
- [ ] §0 holds.

---

### WP-1.5 Image loader and bitmap cache
- **Depends on:** WP-1.1.
- **Branch:** `m1-image-pipeline`
- **Spec:** §6.5 step 3, NFR-1–3, R1, R6.

**Files**
- `commonMain/.../data/image/BitmapLruCache.kt`: a pure LRU, capacity 5, keyed by `(id, targetPx)`.
- `iosMain/.../platform/PhotoKitMediaLoader.kt`: `PHCachingImageManager`.
- `di/IosModule.kt`: bindings.

**Tasks**
1. `image(id, targetPx)`: `callbackFlow`. `requestImageForAsset` with `deliveryMode = opportunistic`, `resizeMode = fast`, `contentMode = aspectFit`, and `networkAccessAllowed = settings.includeCloudOnly`. Each callback: take the JPEG/HEIC bytes (use `requestImageDataAndOrientation`, or encode the `UIImage` with `UIImageJPEGRepresentation`), decode with `org.jetbrains.skia.Image.makeFromEncoded(...).toComposeImageBitmap()` **on `Dispatchers.Default`**, and emit. The degraded image is emitted first and the full one second. Close the flow after the non-degraded result. `awaitClose { cancelImageRequest(requestId) }`.
2. Report iCloud status. If the info dict says `PHImageResultIsInCloudKey` / a network download is in progress, expose it so the card can show the cloud badge (R6). Suggested: `image()` emits a small `LoadedImage(bitmap, isDegraded, isCloud)` instead of a bare `ImageBitmap`. **This changes §6.4, so update the spec.**
3. `prefetch` / `cancelPrefetch` call `startCachingImagesForAssets` / `stopCachingImagesForAssets` with the same options and size.
4. Every decoded full bitmap goes through `BitmapLruCache` (max 5, NFR-3). Evicted entries are released.
5. Cap `targetPx` at the screen size in pixels. Callers pass the card size × screen scale.

**Tests**
- `BitmapLruCacheTest`: `NFR-3 cache never holds more than 5 bitmaps`, `NFR-3 least recently used bitmap is evicted first`.
- `FakeMediaLoader` emits degraded then full, so later ViewModel and UI tests can use it.

**Done when**
- [ ] Opening a single asset on the simulator shows the degraded image, then the sharp one.
- [ ] Cancelling the collector cancels the PhotoKit request. Prove it with a debug log, then remove the log.
- [ ] `scripts/validate.sh --full` passes.
- [ ] Spec §6.4 is updated if the `image()` return type changed.
- [ ] §0 holds.

---

### WP-1.6 Deck use cases
- **Depends on:** WP-1.1.
- **Branch:** `m1-deck-usecases`
- **Spec:** §6.5, FR-6, FR-9, FR-12–15.

**Files** (`commonMain/.../domain/usecase/`)
- `BuildDeckUseCase.kt`, `RecordDecisionUseCase.kt`, `UndoDecisionUseCase.kt`, `DuplicateGrouper.kt`.

**Tasks**
1. `BuildDeckUseCase(source, typeFilter, seed): List<String>` takes `assetIds(source, type)` and subtracts `decidedIds()` (FR-9). It then orders the result:
   - `All` uses `shuffled(Random(seed))`, which is deterministic.
   - `Smart(LONG_VIDEOS)`: duration descending. This needs metadata, so either `MediaLibrary.assetIds` returns them pre-sorted (a PhotoKit sort descriptor on `duration`), or the use case pages metadata. **Choose the PhotoKit sort descriptor, and document in §6.5 that the library returns this source pre-sorted.**
   - `Smart(LIKELY_DUPLICATES)`: `DuplicateGrouper` takes `(id, createdAt, width, height)` sorted by date, and groups runs where neighbors are ≤ 2 s apart with identical dimensions. It emits only groups of size ≥ 2, with members next to each other. The metadata comes from a lightweight `MediaLibrary` call; add `duplicateCandidates(type): List<DuplicateKey>` to the interface and spec.
   - Everything else: creation date descending (the library returns it that way).
2. `RecordDecisionUseCase(id, decision, sessionId)` calls `DecisionStore.record` and rethrows on failure. It deliberately has no side effects beyond the store.
3. `UndoDecisionUseCase(id)` calls `DecisionStore.remove(id)`.

**Tests**
- `FR-9 deck excludes kept and queued items`
- `FR-9 deck includes previously skipped items` (in a new session)
- `6-5 shuffle is stable for the same seed`, `6-5 shuffle differs for a different seed`
- `FR-6 duplicates group items within 2 s with equal dimensions`, `FR-6 duplicates ignore same-time items with different dimensions`, `FR-6 duplicates chain a run of 3`
- `FR-6 long videos are longest first`
- `NFR-7 record rethrows store failure`

**Done when**
- [ ] All tests above pass, with no real clock or random seeds.
- [ ] Spec §6.4/§6.5 is updated for `duplicateCandidates` and the pre-sorted long-video source.
- [ ] §0 holds.

---

### WP-1.7 Navigation, onboarding, and permission flow
- **Depends on:** WP-1.1, WP-1.2, WP-1.4.
- **Branch:** `m1-onboarding-permission`
- **Spec:** FR-1–3, §5.8 screen map.

**Files**
- `commonMain/.../ui/navigation/Routes.kt`: `@Serializable` routes `Onboarding`, `Denied`, `Home`, `Albums`, `Months`, `SmartFilters`, `Swipe(sourceJson, typeFilter)`, `Summary`, `Queue`, `DeleteSuccess`, `Settings`.
- `App.kt`: a `NavHost`. The start destination comes from `PermissionViewModel`.
- `presentation/permission/PermissionViewModel.kt` + `PermissionUiState` / `PermissionIntent`.
- `ui/screens/onboarding/OnboardingScreen.kt`, `ui/screens/onboarding/DeniedScreen.kt`.
- `ui/components/LimitedAccessBanner.kt`: wraps `Banner` with "You've given access to N items. Manage."
- Placeholder `HomeScreen` with a "Start shuffle" button, until WP-5.3.

**Tasks**
1. `PermissionViewModel`: on start, reads `authorization()`. `NOT_DETERMINED` + `!onboardingSeen` → Onboarding. The `RequestAccess` intent calls `requestAuthorization()`, then routes: FULL/LIMITED → Home, DENIED → Denied. It also re-checks on app foreground. Expose a `foreground` signal through `SystemActions`, or use the lifecycle `ON_RESUME`.
2. Onboarding: `display` title, a short explanation of keep / delete / skip and the batch-delete safety, and a `PrimaryButton` "Allow access".
3. Denied: a blocking explanation and "Open Settings" → `SystemActions.openSettings()`.
4. Limited: the banner on Home with N = `assetIds(All, ALL).size`. Tapping it calls `presentLimitedLibraryPicker()`, and the count refreshes after `changes()` emits. In this WP, refresh on resume; WP-3.2 adds `changes()`.

**Tests** (ViewModel, Turbine)
- `FR-1 first launch shows onboarding then requests readWrite`
- `FR-2 limited access shows banner with item count`
- `FR-3 denied access routes to blocking screen and Open Settings calls system action`
- `FR-1 returning user with access skips onboarding`

**Done when**
- [ ] On a reset simulator: Onboarding → system prompt → Home, for "Allow Full Access". "Limit Access" shows the banner. "Don't Allow" shows the Denied screen, and the Open Settings button opens Settings.
- [ ] Screens match the §5.8 screen map. Tokens only.
- [ ] `scripts/validate.sh --full` passes.
- [ ] §0 holds.

---

### WP-1.8 SwipeViewModel core (button-driven)
- **Depends on:** WP-1.3, WP-1.6.
- **Branch:** `m1-swipe-viewmodel-core`
- **Spec:** §6.5 steps 2–3 and 5, §6.6, FR-12–14, FR-19, FR-20, NFR-7.

**Files**
- `presentation/swipe/SwipeViewModel.kt`, `SwipeUiState.kt`, `SwipeIntent.kt`, `CardState.kt`.

**Tasks**
1. Constructor args: `source`, `typeFilter`, plus the use cases, `MediaLibrary`, `MediaLoader`, `SessionStore`, `Clock`, and a seed provider (injected).
2. On init: create a session (seed stored, §6.5), call `BuildDeckUseCase`, and load metadata for `deck[0 until 20]`. Load the next page when `index` comes within 5 of the end of the loaded page.
3. `Keep` / `Delete` / `Skip` intents: (a) `RecordDecisionUseCase` first; (b) **only on success** advance `index`, update `session.position`, and emit the new `current`/`next`. (c) On failure, keep `index`, emit `toast = SaveFailed`, and set a `revertToken` / event so the card animates back (NFR-7).
4. Prefetch: after each advance, call `mediaLoader.prefetch(deck[index..index+3])` and `cancelPrefetch` for items that are no longer in range.
5. `progress = Progress(reviewed = index, total = deck.size)`. `queueCount` comes from `DecisionStore.deleteQueue().map { it.size }`.
6. Deck exhausted → `current = null` (the summary comes in WP-2.2).

**Tests**
- `FR-12 keep records KEEP then advances`
- `FR-13 delete records DELETE then advances`
- `FR-14 skip records SKIP for this session only`
- `NFR-7 failed write does not advance and reverts card` *(required safety test, §8.2)*
- `NFR-7 decision is persisted before index changes` (assert the fake's call order against the state emissions)
- `FR-19 progress counts reviewed over total`
- `6-5 metadata loads in pages of 20`
- `6-5 prefetch requests current plus next 3`

**Done when**
- [ ] All tests pass, using fakes only.
- [ ] `SwipeUiState` matches §6.6. Any additional fields are added to §6.6.
- [ ] §0 holds.

---

### WP-1.9 M1 integration: deck screen with real photos, plus spikes
- **Depends on:** WP-1.5, WP-1.7, WP-1.8.
- **Branch:** `m1-deck-screen`
- **Spec:** M1 "Done when" (§9), NFR-1, R1, R2.

**Tasks**
1. A simple `SwipeScreen`: the top card plus the next card underneath (no gestures yet), and three temporary buttons (Keep / Delete / Skip) using `CircleIconButton`. The image comes from `MediaLoader.image` via a `rememberMediaImage(id, size)` helper that collects the flow and shows the degraded image first.
2. Home placeholder "Shuffle everything" → Swipe(All, ALL).
3. **Spike R2** (time-boxed, on a throwaway branch `spike-video-interop`, not merged): put an `AVPlayerLayer` in a `UIKitView` inside a rotated, translated Box, then record whether it clips or rotates correctly. Write the findings into REQUIREMENTS §10 R2 ("confirmed fallback needed" / "not needed").
4. **R1 check**: swipe through 50 photos quickly via the buttons on the simulator, and watch for main-thread decode. Profiling on a device is 👤.

**Done when**
- [ ] On the seeded simulator: launch → Home → Shuffle shows real photos. The buttons record decisions, and after killing and relaunching the app, kept items don't come back (FR-20, FR-9).
- [ ] R2 findings are recorded in §10.
- [ ] 👤 On a device, the next card shows at least a degraded image within 100 ms of tapping a button (NFR-1). Use Instruments Time Profiler or os_signpost, and record the numbers.
- [ ] `scripts/validate.sh --full` passes.
- [ ] **M1 milestone complete** (§9): permission flow, shuffle-all deck with real photos, image pipeline meets NFR-1.

---

## 3. Milestone M2 — Swiping

### WP-2.1 SwipeCard gesture component
- **Depends on:** WP-1.2 (can start once that's merged; doesn't need the ViewModel).
- **Branch:** `m2-swipe-gestures`
- **Spec:** §5.6 (all of it), §5.7 `SwipeCard` / `VerdictStamp`, rule 4.

**Files**
- `ui/components/swipe/SwipeSpec.kt`: **every** number from §5.6 as a named constant. `COMMIT_FRACTION_HORIZONTAL = 0.35f`, `COMMIT_FRACTION_UP = 0.25f`, `FLING_VELOCITY_DP_S = 1000f`, `MAX_ROTATION_DEG = 12f`, `FLY_OFF_MS = 220`, `UNDO_RETURN_MS = 260`, `NUDGE_DP = 30.dp`, `NUDGE_MS = 80`, `SNAP_DAMPING = 0.7f`, `SNAP_STIFFNESS = Spring.StiffnessMediumLow`, `UNDER_SCALE_FROM = 0.94f`, `UNDER_ALPHA_FROM = 0.6f`, `EDGE_GLOW_MAX_ALPHA = 0.30f`, `STAMP_ROTATION_DEG = 12f`, `REDUCE_MOTION_CROSSFADE_MS = 150`.
- `ui/components/swipe/SwipeMath.kt`: pure functions. `rotationFor(dx, width)`, `stampAlpha(offset, threshold)`, `verdictFor(offset, velocity, size): Verdict?`, `underCardScale/Alpha(progress)`.
- `ui/components/swipe/SwipeCard.kt`, `VerdictStamp.kt`, `EdgeGlow.kt`, `SwipeCardState.kt` (a hoistable state with `suspend fun flyOff(verdict)`, `suspend fun returnFrom(verdict)`, and `snapBack()`).

**Tasks**
1. The drag uses `pointerInput` + `VelocityTracker` and an `Animatable<Offset>`. Horizontal drag always belongs to the card (§5.6).
2. Rotation pivots at the bottom center (`graphicsLayer { transformOrigin = TransformOrigin(0.5f, 1f) }`), clamped to ±12°.
3. Stamps: KEEP at the top-left, rotated −12°; DELETE at the top-right, rotated +12°; SKIP at the bottom center. 3 dp stroke, radius 8, padding 8/16, `stamp` typography, verdict color. Opacity is linear up to the threshold.
4. Edge glow: a gradient on the swipe side in the verdict color, alpha ∝ progress, max 0.30.
5. Release past the threshold or with a fast fling → fly off for 220 ms with `FastOutLinearInEasing` along the release vector until off-screen, then call `onCommit(verdict)`. Below the threshold → spring back.
6. Crossing the threshold in either direction → `onThresholdCrossed()`. The caller maps this to `Haptics.selection()`.
7. Button path: `state.flyOff(verdict)` does an 80 ms 30 dp nudge, then the normal fly-off.
8. Undo: `state.returnFrom(verdict)` flies the card back in from the side it left, 260 ms, `FastOutSlowInEasing`.
9. Reduce Motion (`reduceMotion: Boolean` param): no rotation or fly-off. Commits use a 150 ms crossfade. Stamps still show.
10. Card underneath: scale 0.94→1.0 and alpha 0.6→1.0, tied to the top card's drag progress.
11. The top card gets a shadow (blur 24, 40% black). No other shadows.
12. Tap / double-tap / long-press are passed out as callbacks (`onTap`, `onDoubleTap`, `onLongPress`). Double-tap toggles aspect-fit / aspect-fill inside the card.
13. Revert after a failed save (NFR-7): `state.returnFrom(verdict)` is reused.

**Tests**
- `SwipeMathTest`: `5-6 rotation is clamped to 12 degrees`, `5-6 horizontal commit at 35 percent width`, `5-6 up commit at 25 percent height`, `5-6 fling over 1000 dp per s commits below threshold`, `5-6 stamp alpha is linear to threshold`, `5-6 under card scale tracks drag progress`.
- `SwipeCardTest` (`runComposeUiTest`): `5-6 drag past threshold commits KEEP`, `5-6 drag left past threshold commits DELETE`, `5-6 drag up past threshold commits SKIP`, `5-6 short drag snaps back without commit`, `5-6 reduce motion commits without rotation`.

**Done when**
- [ ] Every §5.6 number is in `SwipeSpec.kt` and nowhere else (grep for `0.35`, `220`, etc. in `ui/`).
- [ ] All tests pass.
- [ ] The card works on the simulator in a debug harness screen.
- [ ] 👤 On a physical device: the drag feels right at 120 Hz with no hitches (Instruments Animation Hitches). Thresholds and haptic timing get checked against §5.6.
- [ ] §0 holds.

---

### WP-2.2 SwipeViewModel: undo, favorites, mute, summary, session resume
- **Depends on:** WP-1.8.
- **Branch:** `m2-swipe-viewmodel-full`
- **Spec:** FR-15, FR-21, FR-22, FR-16 (mute state only), §6.5 step 1 (seed survives process death).

**Tasks**
1. **Undo (FR-15).** An `ArrayDeque<UndoEntry(id, verdict, index)>` capped at 50 (drop the oldest). `Undo` → `UndoDecisionUseCase(id)` first, then `index--`, `canUndo` updated, and an event `ReturnCard(verdict)` so the UI plays the return animation. Also emit the "Undone" toast. If the undo write fails, the state stays as it is and the toast shows an error.
2. **Favorites (FR-22).** `Delete` on a `current.isFavorite` item doesn't record yet. Instead it emits `toast = FavoriteConfirm` and `pendingFavoriteDelete = id`, and the card goes back. `ConfirmFavoriteDelete` → record DELETE and advance. `DismissToast` → clear.
3. **Mute (FR-16).** `muted` starts `true`. `ToggleMute` flips it for this ViewModel's lifetime (the session).
4. **Summary (FR-21).** When `index == deck.size`: `finished = SessionSummary(kept, queued, skipped)` counted from this session's decisions, and `SessionStore.end(id)`.
5. **Resume.** If `SessionStore.openSession()` matches the source and filter, rebuild with the same seed and restore `position`. Otherwise start a new session. Previously skipped items in the same session stay excluded.
6. One-shot UI events (`ReturnCard`, `FlyOff` for button taps, `Haptic`) go through a `Channel` exposed as a `Flow`, or a consumable field in the state. Document which one you chose in §6.6.

**Tests**
- `FR-15 undo restores last card` *(named in AGENTS.md)*
- `FR-15 undo stack holds at most 50 steps`
- `FR-15 undo removes the persisted decision before moving back`
- `FR-22 deleting a favorite asks for confirmation before queueing`
- `FR-22 dismissing favorite confirm queues nothing`
- `FR-16 mute toggles and persists across cards`
- `FR-21 exhausted deck emits session summary with counts`
- `6-5 resumed session keeps shuffle order and position`

**Done when**
- [ ] All tests pass. `SwipeIntent` matches §6.6 (`Keep, Delete, Skip, Undo, ToggleMute, ConfirmFavoriteDelete, DismissToast`). Any additions are documented in §6.6.
- [ ] §0 holds.

---

### WP-2.3 Swipe screen assembly, summary, haptics, manual checklist
- **Depends on:** WP-2.1, WP-2.2.
- **Branch:** `m2-swipe-screen`
- **Spec:** §5.7 `TopBar` / `ActionBar` / `SwipeCard` metadata, §5.8 Swipe + Summary + Empty, FR-11, FR-18 (date + duration only, badges in WP-4.2), FR-19, FR-21.

**Files**
- `ui/components/TopBar.kt`: close ×, centered progress `128 / 2,340` (`caption`, tnum, grouping separators), 2 dp progress line, queue button with `QueueBadge`.
- `ui/components/ActionBar.kt`: Undo 40 at the left edge, then Delete 64 / Skip 48 / Keep 64 with a 24 dp gap, using the tinted fills from §5.3.
- `ui/components/CardMetadata.kt`: the bottom 96 dp scrim gradient, date (`caption`, `textPrimary`), duration for videos.
- `ui/screens/swipe/SwipeScreen.kt` (replaces the WP-1.9 version), `ui/screens/summary/SummaryScreen.kt`, `ui/components/EmptyState.kt`.
- `iosMain/.../platform/IosHaptics.kt`: `UISelectionFeedbackGenerator`, `UIImpactFeedbackGenerator(light/medium)`, `UINotificationFeedbackGenerator`. It respects `SettingsStore.haptics`.
- `docs/manual-test.md` (created here, §8.2): sections **Swipe**, **Delete** (stub, filled by WP-3.3), **Video** (stub), **Permissions**, **Settings** (stub).

**Tasks**
1. Wire `SwipeCard.onCommit` → `onIntent(Keep/Delete/Skip)`. Wire the ActionBar buttons → `cardState.flyOff(verdict)`, which then calls `onCommit`. Undo → `onIntent(Undo)`, and the `ReturnCard` event → `cardState.returnFrom()`.
2. Haptics: `selection` on threshold crossing, `impactLight` on Keep/Skip commit, `impactMedium` on Delete commit (§5.6).
3. Tap on a photo toggles the chrome (TopBar + metadata).
4. Toasts: "Undone", "Couldn't save — try again" (NFR-7), and the favorite confirm ("This is a favorite — delete anyway?" with a "Delete" action) in the `warning` style.
5. Summary: a centered `display` number with verdict-colored labels "Kept N" / "Queued N" / "Skipped N". The CTA is "Review N to delete" → Queue if N > 0, else "Back home".
6. Empty deck at start → "Nothing left here 🎉" + "Try another source" → Home.
7. Close × → Home (the session stays open, so it can resume).

**Tests** (`runComposeUiTest` with fakes)
- `FR-12 tapping Keep button sends Keep intent`, and the same for Delete and Skip
- `FR-15 undo button disabled when stack empty`
- `FR-19 top bar shows reviewed over total with grouping`
- `FR-21 summary shows kept queued skipped`
- `NFR-8 every action bar button has a content description`

**Done when**
- [ ] On the seeded simulator, a full session works end to end: swipe in all 3 directions, use the buttons, undo multiple times, delete a favorite (toast appears), finish the deck → summary → (queue placeholder).
- [ ] Screens match the Swipe and Summary wireframes in §5.8 (attach screenshots).
- [ ] Killing the app mid-session and relaunching resumes at the same card (FR-20).
- [ ] `docs/manual-test.md` exists, with a Swipe section that has step-by-step checks for every §5.6 row.
- [ ] 👤 On a device: run the Swipe section of `docs/manual-test.md`, including haptics.
- [ ] `scripts/validate.sh --full` passes.
- [ ] **M2 milestone complete.**

---

## 4. Milestone M3 — Review and delete

### WP-3.1 CommitDeletionsUseCase and QueueViewModel
- **Depends on:** WP-1.3 (can run in parallel with M2).
- **Branch:** `m3-commit-deletions`
- **Spec:** FR-23–28, NFR-5, §6.7, hard rule 1.

**Files**
- `domain/usecase/CommitDeletionsUseCase.kt`, `domain/usecase/PruneQueueUseCase.kt`, `domain/usecase/RestoreFromQueueUseCase.kt`.
- `presentation/queue/QueueViewModel.kt`, `QueueUiState.kt`, `QueueIntent.kt`.

**Tasks**
1. `CommitDeletionsUseCase(confirmation: InAppDeleteConfirmation)`. `InAppDeleteConfirmation` has an `internal` constructor and can only be created by `QueueViewModel` after the user taps "Delete" in the in-app confirm dialog. It carries the exact ID list the user saw. The use case:
   - re-reads `deleteQueue()` and deletes only IDs that are in both the confirmation and the queue;
   - calls `MediaLibrary.delete(ids)` **once** (FR-25);
   - `Deleted` → `markDeleted(ids)` → returns `Success(count)`;
   - `Cancelled` → no store calls, returns `Cancelled` (FR-27);
   - `Failed(msg)` → no store calls, returns `Error(msg)`.
2. `PruneQueueUseCase`: on queue load, `items(queueIds)` drops IDs PhotoKit no longer returns, and `remove()` deletes them from the store silently (FR-28).
3. `RestoreFromQueueUseCase(id)`: `record(id, KEEP, sessionId = "restore")` (FR-24).
4. `QueueViewModel`: state = `items` (newest first, FR-23), `selected` (for full-screen), `confirming: Boolean`, `deleting: Boolean`, `result`. Intents: `Open(id)`, `Close`, `Restore(id)`, `RequestDelete`, `ConfirmDelete`, `CancelConfirm`. `ConfirmDelete` → haptic `notificationSuccess` on success → navigate to DeleteSuccess. On error → toast; the queue is unchanged.

**Tests** *(the three §8.2 safety tests are required)*
- `NFR-5 deleting with no in-app confirmation does nothing`. Test both at the ViewModel level (RequestDelete alone never calls `delete`) and at the type level (the confirmation can't be built outside the package).
- `FR-27 cancelling the iOS dialog leaves the queue exactly as it was`
- `FR-25 commit calls MediaLibrary delete exactly once with every queued id`
- `FR-26 confirmed delete marks items DELETED and empties the queue`
- `6-7 delete failure shows a toast and keeps the queue`
- `FR-28 queue load prunes ids that no longer exist`
- `FR-24 restore removes item from queue and records KEEP`
- `FR-23 queue is ordered newest decision first`

**Done when**
- [ ] All tests pass. `scripts/check-rules.sh` confirms `CommitDeletionsUseCase.kt` is the only commonMain caller of `MediaLibrary.delete`.
- [ ] REQUIREMENTS §6.7 / NFR-5 mention the confirmation token mechanism.
- [ ] §0 holds.

---

### WP-3.2 PhotoKit delete and the library change observer
- **Depends on:** WP-1.4.
- **Branch:** `m3-photokit-delete`
- **Spec:** FR-25–28, §6.8, §8.3 check-rules (`performChanges` only in `PhotoKitMediaLibrary.kt`).

**Tasks**
1. `delete(ids)`: fetch the `PHAsset`s by ID, then `PHPhotoLibrary.sharedPhotoLibrary().performChanges({ PHAssetChangeRequest.deleteAssets(assets) }, completionHandler)`, bridged with `suspendCancellableCoroutine`. Map the result: success → `Deleted`; `NSError` with domain `PHPhotosErrorDomain` and code `PHPhotosErrorUserCancelled` (3072) → `Cancelled`; anything else → `Failed(localizedDescription)`. Fetching zero assets → `Deleted` without a call.
2. `changes()`: a `callbackFlow` that registers a `PHPhotoLibraryChangeObserver` (an `NSObject` subclass in Kotlin/Native), emits `Unit` on `photoLibraryDidChange`, and unregisters in `awaitClose`. Conflate it.
3. Wire `changes()` into the Home limited-access count (WP-1.7) and into `QueueViewModel` (re-run the prune).
4. Keep `performChanges` / `deleteAssets` inside `PhotoKitMediaLibrary.kt` only.

**Done when**
- [ ] On the simulator, with a temporary debug trigger or via WP-3.3 if it's merged: confirm → the items appear in Photos → Recently Deleted. Cancel → nothing changes.
- [ ] Deleting a photo in the Photos app while Swipe Cleaner is open prunes it from the queue (FR-28).
- [ ] `scripts/check-rules.sh` passes. `scripts/validate.sh --full` passes.
- [ ] §0 holds.

---

### WP-3.3 Review queue UI and delete success
- **Depends on:** WP-3.1, WP-3.2, WP-2.3.
- **Branch:** `m3-queue-ui`
- **Spec:** §5.7 `GridTile`, §5.8 Review queue, FR-23–27.

**Files**
- `ui/components/GridTile.kt`: square, radius 8, 2 dp gap, video duration at the bottom right, 3 dp `keep` ring when selected.
- `ui/screens/queue/QueueScreen.kt`: header "‹ Back   To delete (N)", hint "Tap an item to restore it.", a `LazyVerticalGrid(3)`, a pinned destructive `PrimaryButton` "Delete N items", and the caption "They'll move to Recently Deleted for 30 days."
- `ui/screens/queue/QueueItemViewer.kt`: full screen, with a **Restore** action (`keep` styled).
- `ui/components/ConfirmDialog.kt`: the in-app confirmation ("Delete N items?", destructive confirm + cancel).
- `ui/screens/summary/DeleteSuccessScreen.kt`: `display` count, "Deleted N", "Back home".
- Thumbnails: `MediaLoader.image(id, tileSizePx)` (small target size). Don't run tiles through the 5-slot full-screen LRU. Give thumbnails a separate, size-bounded cache, or none.

**Tasks**
1. Wire the queue badge in the TopBar, the Summary CTA, and the Home banner to the Queue route.
2. Empty queue → empty state.
3. Fill in the **Delete** section of `docs/manual-test.md`: confirm, cancel at the in-app dialog, cancel at the iOS dialog, an error case (e.g. airplane mode + iCloud asset, or delete the item elsewhere first), and the Recently Deleted check.

**Tests**
- `FR-23 queue grid shows 3 columns`
- `FR-24 restore in viewer sends Restore intent`
- `NFR-5 delete button opens in-app confirm and does not delete`
- `NFR-8 grid tiles have content descriptions with date and type`

**Done when**
- [ ] Simulator walk-through: swipe left 5 items → queue shows 5, newest first → restore 1 → "Delete 4 items" → in-app confirm → iOS dialog → **Delete** → success screen → Photos shows 4 in Recently Deleted. Repeat with **Don't Allow** in the iOS dialog: the queue is still 4 and no error shows.
- [ ] The screen matches the §5.8 wireframe (screenshot attached).
- [ ] 👤 On a device: run the Delete section of `docs/manual-test.md` (confirm, cancel, error), including the `notificationSuccess` haptic.
- [ ] `scripts/validate.sh --full` passes.
- [ ] **M3 milestone complete.**

---

## 5. Milestone M4 — Video and Live Photos

### WP-4.1 VideoPlayer expect/actual
- **Depends on:** WP-1.9 (R2 spike findings).
- **Branch:** `m4-video-player`
- **Spec:** §6.4 `VideoPlayer`, §6.5 step 4, FR-16, FR-17, R2.

**Files**
- `commonMain/.../ui/components/media/VideoPlayer.kt`: `expect fun VideoPlayer(id: String, muted: Boolean, playing: Boolean, onProgress: (Float) -> Unit, modifier: Modifier, livePhoto: Boolean = false)`. This is the **only** expect/actual composable.
- `iosMain/.../platform/VideoPlayerView.kt`: the `actual`. A `UIKitView` hosting a `UIView` subclass whose `layerClass` is `AVPlayerLayer`, using `AVQueuePlayer` + `AVPlayerLooper` for looping.
- `iosMain/.../platform/VideoItemCache.kt`: preloads an `AVPlayerItem` for the next video (`PHImageManager.requestPlayerItemForVideo`, `networkAccessAllowed` per settings).
- A `VideoPreloader` interface in `data/` + `iosMain` impl + fake, called by `SwipeViewModel` alongside image prefetch.

**Tasks**
1. `muted` → `player.muted`. `playing` → play/pause. A periodic time observer (every ~100 ms) → `onProgress(current/duration)`. Remove the observer and pause on dispose.
2. Live Photos (`livePhoto = true`): load the `PHAssetResourceTypePairedVideo` through `PHAssetResourceManager.writeDataForAssetResource` into a temp file (cache by ID, delete on dispose), then play it once with no loop. This keeps a single expect/actual (rule 5). Document the approach in §6.4.
3. Apply the R2 result. If the spike showed clipping or rotation problems, show the poster frame (the image from `MediaLoader`) while the card is being dragged or animated, and mount the player only when the card is at rest (`SwipeCardState.isIdle`).
4. Respect `SettingsStore.autoplayVideos`. If it's off, show the poster with a play affordance; tap to play.

**Done when**
- [ ] On the simulator, a fixture video autoplays muted and loops, and progress callbacks fire.
- [ ] The next video starts without a visible stall when it reaches the top (preloaded item).
- [ ] The R2 fallback is implemented or explicitly found unnecessary, and §10 is updated.
- [ ] `scripts/validate.sh --full` passes. `scripts/check-rules.sh` passes (no new expect/actual composables).
- [ ] §0 holds.

---

### WP-4.2 Video and Live Photo UX in the swipe screen, and badges
- **Depends on:** WP-4.1, WP-2.3.
- **Branch:** `m4-video-ux`
- **Spec:** FR-16–18, §5.6 "Other gestures", §5.7 `SwipeCard` badges.

**Tasks**
1. Tap on a video card → `ToggleMute`, which is remembered for the session.
2. Scrubber: a thin progress track with elapsed time (`caption`, tnum), in the **action-bar area outside the card** (§5.6). Dragging it seeks. Only show it for videos.
3. Long-press on a Live Photo → `VideoPlayer(livePhoto = true, playing = true)` overlaid on the still image. Releasing stops it.
4. Badges in the metadata row: Live (`live` icon), iCloud (`cloud`, shown when `LoadedImage.isCloud` or `isCloudOnly == true`, R6), favorite (`favorite`). Duration `m:ss` for videos.
5. Fill in the **Video** section of `docs/manual-test.md`.

**Tests**
- `FR-16 tap on video card toggles mute`
- `FR-16 scrubber only shown for videos`
- `FR-18 metadata shows date, duration and badges`
- `FR-17 long press on live photo starts playback`
- `5-6 horizontal drag on card never moves the scrubber`

**Done when**
- [ ] The simulator walk-through with fixtures (short video, >60 s video, Live Photo) passes the Video section of `docs/manual-test.md`.
- [ ] 👤 On a device: an iCloud-only video shows the cloud badge and then plays. Swiping a playing video stays smooth (Animation Hitches).
- [ ] `scripts/validate.sh --full` passes.
- [ ] **M4 milestone complete.**

---

## 6. Milestone M5 — Sources and filters

### WP-5.1 PhotoKit sources, filters, and counts
- **Depends on:** WP-1.4, WP-1.6.
- **Branch:** `m5-photokit-sources`
- **Spec:** FR-4–8, NFR-4.

**Tasks**
1. `sources()` is too coarse for NFR-4, because months need a scan. **Split it** into `albums(): List<AlbumSource>` (user albums + main smart albums: Favorites, Recents, Videos, Selfies, Screenshots, Live Photos, Bursts, with `estimatedAssetCount` / a fetch count and a cover asset ID), `months(type): List<MonthBucket>` (year, month, count, newest first), and `smartCounts(type): Map<SmartFilter, Int>`. Update §6.4 and remove `sources()`.
2. `assetIds` for each `DeckSource`:
   - `Album(id)`: `fetchAssetsInAssetCollection`.
   - `Month(y, m)`: a `creationDate` range predicate.
   - `Smart(SCREENSHOTS)`: `(mediaSubtypes & PHAssetMediaSubtypePhotoScreenshot) != 0`.
   - `Smart(LONG_VIDEOS)`: `mediaType == video AND duration >= 60`, sorted by `duration` descending.
   - `Smart(BURSTS)`: `includeAllBurstAssets = true` and `representsBurst`/`burstIdentifier != nil`. Check which predicate PhotoKit supports and document it.
   - `Smart(SELFIES)`: the `smartAlbumSelfPortraits` collection.
   - `Smart(LIKELY_DUPLICATES)`: implement `duplicateCandidates(type)`, which enumerates `creationDate` + `pixelWidth/Height` only, sorted by date.
   - The type filter is ANDed into every predicate (FR-5).
3. `months`: enumerate `creationDate` only on `Dispatchers.Default`, and bucket in Kotlin. This is called lazily when the By month screen opens, **never at Home startup**.
4. Thumbnails for album covers come from `MediaLoader.image(coverId, 40 dp × scale)`.

**Tests** (`commonTest`, pure helpers extracted)
- `FR-7 month buckets are newest first with counts`
- `FR-6 long video threshold is 60 seconds inclusive`
- `FR-5 type filter applies on top of every source` (via `FakeMediaLibrary`, which mirrors the same contract)

**Done when**
- [ ] On the seeded simulator, each smart filter returns the fixture expected for it (see `docs/fixtures/README.md`), and the type filter narrows each one correctly.
- [ ] The spec §6.4 interface matches the code.
- [ ] 👤 On a device with a large library: `smartCounts` and `albums` together return within the NFR-4 startup budget. Time `months` separately.
- [ ] `scripts/validate.sh --full` passes.
- [ ] §0 holds.

---

### WP-5.2 LoadSourcesUseCase and HomeViewModel
- **Depends on:** WP-5.1 (interface only; can start against the fakes once 5.1's interface change is merged), WP-1.3.
- **Branch:** `m5-home-viewmodel`
- **Spec:** FR-4, FR-5, FR-9, FR-10, NFR-4.

**Tasks**
1. `LoadSourcesUseCase`: combines `albums()`, `smartCounts(type)`, and the shuffle count. **Counts exclude decided IDs (FR-9).** Computing exact counts means subtracting `decidedIds()` from each source's ID list. Do this lazily per row (show the PhotoKit count first, then refine), and document the choice in §6.5.
2. `HomeViewModel`: state = `typeFilter`, `shuffleCount`, `smartRows`, `stats` (reviewed / kept / deleted from `DecisionStore.stats()`), `queueCount`, `access` (for the limited banner). Intents: `SetTypeFilter`, `OpenSource(source)`, `OpenQueue`, `OpenSettings`. It refreshes on `MediaLibrary.changes()`.
3. Home must be interactive before the counts arrive (NFR-4). The rows render immediately with a placeholder count.

**Tests**
- `FR-10 home shows lifetime stats and queue size`
- `FR-5 changing type filter reloads counts`
- `FR-9 source counts exclude decided items`
- `NFR-4 home state is emitted before counts finish loading`

**Done when**
- [ ] All tests pass. §6.5 documents how counts are computed.
- [ ] §0 holds.

---

### WP-5.3 Home, Albums, By month, and Smart filter screens
- **Depends on:** WP-5.2, WP-1.7.
- **Branch:** `m5-home-screens`
- **Spec:** §5.7 `SourceRow`, §5.8 Home wireframe, FR-4, FR-7, FR-8, FR-10.

**Files**
- `ui/components/SourceRow.kt`: 64 dp, `surface`, 40 dp thumbnail (radius 8) or accent icon, `headline` title, `callout textSecondary` count, chevron.
- `ui/screens/home/HomeScreen.kt` (replaces the placeholder): the title "Swipe Cleaner" + ⚙, the stats line `1,204 reviewed · 312 deleted`, the queue Banner (only when the queue is > 0), the segmented control, Shuffle / Albums / By month rows, a "SMART FILTERS" caption header, and 5 smart rows.
- `ui/screens/home/AlbumsScreen.kt`, `MonthsScreen.kt` (year sections → month rows, newest first), `SmartFiltersScreen.kt`. The Home rows link to these directly, so this screen is optional; drop it if it's redundant and update the §5.8 map.
- A ViewModel for Albums/Months (lazy loading), or extend `HomeViewModel`. Either way it needs tests.

**Tasks**
1. Every row navigates to `Swipe(source, typeFilter)`.
2. The limited-access banner and the queue banner both show when both apply. Limited goes first.
3. Number formatting uses grouping separators and tabular figures.

**Tests**
- `FR-4 home lists all session sources`
- `FR-10 queue banner hidden when queue is empty`
- `FR-7 months screen groups by year newest first`
- `FR-8 album rows show cover and count`

**Done when**
- [ ] Simulator screenshots match the §5.8 Home wireframe. Every row opens a correctly filtered deck.
- [ ] `scripts/validate.sh --full` passes.
- [ ] **M5 milestone complete.**

---

## 7. Milestone M6 — Polish

### WP-6.1 Settings
- **Depends on:** WP-5.3.
- **Branch:** `m6-settings`
- **Spec:** FR-29–31.

**Tasks**
1. `SettingsViewModel` + `SettingsScreen`: toggles for haptics, include iCloud-only items, and autoplay videos, bound to `SettingsStore`. "Reset all decisions" → confirm dialog → `DecisionStore.clearKeeps()` (FR-30). Version and build text.
2. Make sure the settings take effect: `IosHaptics` checks `haptics`, `PhotoKitMediaLoader` uses `includeCloudOnly` for `networkAccessAllowed`, and when `includeCloudOnly` is off, cloud-only items are excluded from decks (document how they're detected in §6.5, or record that only loading is blocked), and `VideoPlayer` honours `autoplayVideos`.
3. Signing expiry (FR-31): read `embedded.mobileprovision` from the main bundle, extract the plist between `<?xml` and `</plist>`, and read `ExpirationDate`. If anything fails, hide the row. Put the parsing in a pure function with a test fixture string.
4. Fill in the **Settings** section of `docs/manual-test.md`.

**Tests**
- `FR-29 toggles persist through the settings store`
- `FR-30 reset clears keeps but not the delete queue`
- `FR-30 reset requires confirmation`
- `FR-31 provisioning profile expiry is parsed` / `FR-31 missing profile hides expiry`

**Done when**
- [ ] All tests pass. On the simulator, each toggle visibly changes behavior. The expiry row is hidden on the simulator, because there's no profile.
- [ ] 👤 On a device, the expiry date matches the free-provisioning 7-day date.
- [ ] `scripts/validate.sh --full` passes.
- [ ] §0 holds.

---

### WP-6.2 Accessibility and Reduce Motion pass
- **Depends on:** WP-6.1 (all screens exist).
- **Branch:** `m6-accessibility`
- **Spec:** NFR-8, §5.6 Reduce Motion, §5.5 touch targets.

**Tasks**
1. Audit every screen. Each control has a `contentDescription` and a role, and touch targets are at least 44 dp. The swipe card exposes custom accessibility actions Keep / Delete / Skip / Undo, so VoiceOver users don't need gestures. The card description reads "Photo, March 14 2021, favorite".
2. `SystemActions.isReduceMotionEnabled()` → `SwipeCard(reduceMotion = …)` everywhere, plus crossfades instead of slides in navigation transitions.
3. Dynamic Type is out of scope for v1. Note it in REQUIREMENTS if it's not already there.

**Tests**
- `NFR-8 every screen's clickable nodes have content descriptions`: one test per screen, walking the semantics tree for `hasClickAction()` without `contentDescription` or text.
- `NFR-8 swipe card exposes keep delete skip accessibility actions`
- `5-6 reduce motion disables rotation and fly-off`

**Done when**
- [ ] The tests pass. With the simulator Accessibility Inspector, you can reach and use every control.
- [ ] 👤 On a device with VoiceOver on: complete a session and a batch delete without touch gestures. With Reduce Motion on: cards crossfade and stamps still show.
- [ ] `scripts/validate.sh --full` passes.
- [ ] §0 holds.

---

### WP-6.3 Performance pass (👤 mostly human)
- **Depends on:** WP-6.2.
- **Branch:** `m6-performance`
- **Spec:** NFR-1–4, R1, §8.2 Performance row.

**Tasks**
1. Add `os_signpost` intervals (via `platform.os`, iosMain only): swipe commit → next card degraded image shown, → full image shown; cold start → Home interactive.
2. Write `docs/perf.md` with the measurement procedure and a results table. For each NFR: target, measured value, device, library size, date.
3. 👤 Run Instruments on a physical device with a 10k+ library: Time Profiler (NFR-1, NFR-4), Allocations (NFR-3 < 300 MB, ≤ 5 full bitmaps), Animation Hitches (NFR-2).
4. A sub-agent can then fix any misses, as separate small WPs, e.g. widening the prefetch window or lowering the decode size.

**Done when**
- [ ] `docs/perf.md` has measured values for NFR-1 to NFR-4, each one passing or with a linked follow-up WP.
- [ ] `scripts/validate.sh --full` passes.

---

### WP-6.4 Release-readiness sweep
- **Depends on:** WP-6.3.
- **Branch:** `m6-release-sweep`

**Tasks**
1. Coverage audit: for every FR-1…FR-31 and NFR-1…NFR-8, `grep -r "FR-12 " composeApp/src/*Test` (etc.) finds a test, or REQUIREMENTS / `docs/manual-test.md` says why it's manual-only. Produce the table in `docs/coverage.md`.
2. Resolve the §2 open questions in the spec: tick them, or keep the defaults. Storage estimate (R3): skip it unless it's reliably available, and record the decision.
3. Re-verify every command in AGENTS.md "Commands", and update the verified date.
4. Remove debug-only screens and hooks, or keep them behind a debug flag.
5. 👤 Run all of `docs/manual-test.md` on a device, then install with the free Apple ID flow (§8.1).

**Done when**
- [ ] `docs/coverage.md` maps every FR/NFR to a test or a manual-checklist item.
- [ ] All §2 open questions are resolved in the spec.
- [ ] `scripts/validate.sh --full` passes on `main` after the merge.
- [ ] 👤 The full manual checklist passes on a device.
- [ ] **M6 milestone complete. v1 done.**

---

## Tracker

| WP | Title | Milestone | Status |
| --- | --- | --- | --- |
| 1.1 | Domain, interfaces, fakes | M1 | [x] |
| 1.2 | Theme tokens, icons, base components | M1 | [x] |
| 1.3 | SQLDelight store | M1 | [x] |
| 1.4 | PhotoKit library (read) | M1 | [x] |
| 1.5 | Image loader + LRU | M1 | [x] |
| 1.6 | Deck use cases | M1 | [x] |
| 1.7 | Navigation + onboarding/permission | M1 | [x] |
| 1.8 | SwipeViewModel core | M1 | [x] |
| 1.9 | M1 deck screen + spikes | M1 | [x] |
| 2.1 | SwipeCard gestures | M2 | [x] |
| 2.2 | SwipeViewModel full | M2 | [x] |
| 2.3 | Swipe screen + summary + haptics | M2 | [x] |
| 3.1 | CommitDeletions + QueueViewModel | M3 | [x] |
| 3.2 | PhotoKit delete + change observer | M3 | [x] |
| 3.3 | Queue UI + delete success | M3 | [x] |
| 4.1 | VideoPlayer expect/actual | M4 | [x] |
| 4.2 | Video/Live UX + badges | M4 | [x] |
| 5.1 | PhotoKit sources + filters | M5 | [x] |
| 5.2 | LoadSources + HomeViewModel | M5 | [ ] |
| 5.3 | Home + source screens | M5 | [ ] |
| 6.1 | Settings | M6 | [ ] |
| 6.2 | Accessibility + Reduce Motion | M6 | [ ] |
| 6.3 | Performance pass | M6 | [ ] |
| 6.4 | Release-readiness sweep | M6 | [ ] |

## Expected spec edits (call these out when they land)

| WP | REQUIREMENTS section | Change |
| --- | --- | --- |
| 1.1 | §6.4 | Add `DecisionStore.stats()`, `SessionStore`, `SettingsStore` shape, `Haptics`, `SystemActions` |
| 1.2 | §5.4, §5.7 | Font result; component size tokens |
| 1.5 | §6.4 | `image()` emits `LoadedImage(bitmap, isDegraded, isCloud)` |
| 1.6 | §6.4, §6.5 | `duplicateCandidates()`; long-video source returned pre-sorted |
| 1.9 | §10 R2 | Video interop spike result |
| 2.2 | §6.6 | One-shot event mechanism; any extra state fields |
| 3.1 | §6.7, NFR-5 | `InAppDeleteConfirmation` token |
| 4.1 | §6.4 | Live Photo playback through `VideoPlayer(livePhoto = true)`; `VideoPreloader` |
| 5.1 | §6.4 | Replace `sources()` with `albums()`, `months()`, `smartCounts()` |
| 5.2 | §6.5 | How source counts exclude decided items |
| 6.1 | §6.5 | How the iCloud-only toggle excludes items |
