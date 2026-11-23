# WP-7.7 report — FR-7 month-deck order fix + fake/real contract suite

Branch `fr-7-month-order` (base `c727a6f`), 3 commits:

| Commit | What |
| --- | --- |
| `b719e04` | The fix: month + All deck fetches run PhotoKit with the full sorted options + `PhotoKitFetchOptionsTest` (4 tests) |
| `734e654` | `MediaLibraryContract` + fake runner + real PhotoKit runner (14 tests) |
| `b7abaa6` | detekt-driven seed() refactor; partial writes cleaned up on skip |

## Root cause

`PhotoKitSourceFetches.monthAssetIds` built `monthFetchOptions(...)` — the
half-open `creationDate` range predicate, the type filter, **and** the
creation-date-descending sort descriptor — but then called the fetch with
only `options.predicate`, dropping the descriptor. PhotoKit answered in its
natural order (oldest first), so the FR-7 month deck rendered oldest-first.
The fake always sorts in memory, so every existing test passed; no test had
ever asserted on what the real fetch does. The All deck (`allAssetIds`) had
no sort at all and was fixed for the same reason (§6.5 and the
`MediaLibrary.assetIds` KDoc promise creation-date descending; Shuffle
re-orders with its own seeded key, so the change is app-safe).

## What was added

**Fix** (`iosMain/platform/PhotoKitSourceFetches.kt`): `monthAssetIds` and
`allAssetIds` now hand PhotoKit the full sorted options, and each gained a
`fetch: (PHFetchOptions) -> PHFetchResult` seam (default = the production
fetch) so tests can capture the exact options.

**`PhotoKitFetchOptionsTest`** (4 tests, deterministic on *any* host —
no photo library needed): pins that the month fetch passes a single
`creationDate` descending sort descriptor and the half-open range predicate
with the type filter ANDed in, and that the All fetch sorts descending with
no predicate for `ALL`.

**`MediaLibraryContract`** (abstract, 9 shared assertions over 7 test
methods) + **two runners** executing identical assertions:

- `FakeMediaLibraryContractTest` — the behavioral baseline; never skips.
- `PhotoKitMediaLibraryContractTest` — seeds a deterministic 6-asset
  fixture through the PhotoKit write API (photos as rendered UIImages,
  videos encoded to real H.264 with AVAssetWriter; dates 12:00 UTC on fixed
  days in 2025-03 / 2025-04 so no device timezone can shift membership),
  runs the contract against the real fetches, and deletes exactly the
  fixture ids it created afterwards (partial writes are cleaned up too;
  FR-25's app-code delete rule is untouched).

Contract coverage: month deck creation-date descending **and** not-oldest-first
(the regression, plus a date-level check through `items`), month isolation,
`MediaTypeFilter` on month and All decks (FR-5), All deck descending so months
come newest-first (§6.5), `items()` resolving ids in requested order and
dropping unknown ids.

## Test counts

- Baseline: **415 tests, 0 failures, 0 skipped**.
- Now: **433 tests, 0 failures** (415 + 4 fetch-options + 7 fake-contract +
  7 real-contract). `./gradlew :composeApp:iosSimulatorArm64Test` green;
  `ktlintFormat`/`ktlintCheck`, `detekt`, `scripts/check-rules.sh` all clean;
  no bare `@Suppress` (one justified `DEPRECATION` for the offscreen image
  context API, one `BetaInteropApi` opt-in mirroring production
  `NSArray.create` usage).

## Did the real runner run?

**It skipped, by design, on this host.** The Gradle simulator test host has
no app bundle (`bundleId=null`) and no `NSPhotoLibraryUsageDescription`, so
PhotoKit reports denied (status 2). The runner prints
`SKIPPED (WP-7.7): host photo library is not read-write authorized
(status=2)` and each asset-dependent test prints its assumption line.
kotlin.test on Kotlin/Native has no assumption API, so a skipped test is
recorded as passed with an explicit `SKIPPED (WP-7.7): …` stdout line —
never silently. On a dev machine running the suite from the Xcode test host
(granted access), the same seven tests execute against the real library.
The deterministic red→green net that works everywhere is
`PhotoKitFetchOptionsTest`: with the bug temporarily re-injected into
iosMain, `FR-7 the month fetch hands PhotoKit a creationDate descending
sort` **FAILED**; after restoring the fix, all 433 pass.

## Deviations from the brief

1. **API names**: the brief (and my earlier compacted notes) referenced
   `MediaQuery`/`MediaSortOrder`/`resolve` — no such API exists in this
   repo. The contract is written against the real one: `assetIds(DeckSource,
   MediaTypeFilter)`, `DeckSource.Month/All`, `items(ids)` for id resolution.
2. **Red before the fix "against the fake" is impossible** — the fake always
   sorted (that was the bug's blind spot). Red was proven against the real
   fetch path via the options-capture seam instead.
3. The **All deck** was fixed too, not just the month path (see root cause;
   it had no sort at all and contradicted the interface KDoc).
4. The brief mentioned sibling iosTest files (`PhotoKitBridgeDecodeTest.kt`,
   `AlbumExclusionRealPhotoKitTest.kt`) as existing references — they exist
   nowhere in the repo at base; the real runner stands alone.
5. **Environment note**: mid-session the three contract files were
   overwritten twice by an external writer with stale drafts (one referencing
   nonexistent classes like `MediaLibraryContractSuite`). Resolved by
   rewriting against the verified API and committing immediately after each
   green compile. Final committed state compiles and passes; nothing of the
   foreign content survived.

## Extension points left (per WP-8.3 / WP-8.7)

- Burst-deck contract slots (PhotoKit burst semantics need
  `includeAllBurstAssets` seeding; the fake models bursts via `burstId`).
- Partial-delete/resilience scenarios (`failNext…` switches exist on the
  fake; the real runner has no delete-failure injection).
