# Swipe Cleaner — Plan

_Started 2026-11-09. Source of truth for behavior: [`REQUIREMENTS.md`](REQUIREMENTS.md). Rules for agents: [`AGENTS.md`](AGENTS.md). The completed v1 plan (M1–M6, WP-1.1 to WP-6.4) is archived at [`docs/archive/plan-v1.md`](docs/archive/plan-v1.md)._

All functional requirements in the v1 plan shipped. This plan tracks what is still open. WP numbers continue from v1.

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

## 1. Waves

WPs in the same wave don't depend on each other. Run one subagent at a time (AGENTS.md).

- **Wave A:** WP-7.1, WP-7.2, WP-7.3
- **Wave B (only if device numbers call for it):** the conditional items in §4
- **Wave C:** WP-8.1, then WP-8.2 (both need a decision first, see §5)
- **Wave D:** WP-8.3, WP-8.4

## 2. Code follow-ups

### WP-7.1 Stop closing bitmaps that may still be on screen

**Branch:** `nfr-3-bitmap-lifetime`. **Requirements:** NFR-3, §6.5 step 3.

`BitmapLruCache` closes a bitmap on eviction or when `put` replaces the same key (`BitmapLruCache.kt:73`), and its contract requires at most one concurrent collector per key, which nothing enforces. A card promoted from "next" to "current" composes a new collector for the same key, so an overlapping decode can close a bitmap that is being drawn. No crash has been traced to this; it is a latent risk.

- Make a same-key `put` keep the existing bitmap instead of closing it, or otherwise guarantee that a bitmap handed to the UI outlives its use.
- Keep the 5-bitmap budget.
- **Tests:** `NFR-3` cases in `BitmapLruCacheTest` for same-key puts and eviction.

### WP-7.2 Guard Undo against an in-flight decision

**Branch:** `fr-15-undo-decide-race`. **Requirements:** FR-15, NFR-7.

`SwipeViewModel.undo()` can rewind the index while a `decide()` write is still completing, which re-shows a decided card. The data stays durable. Add a decide-in-flight guard, mirroring the existing double-tap and single-flight undo handling.

- **Tests:** `FR-15` case in `SwipeViewModelUndoTest` with a suspended decision write.

### WP-7.3 Close the session for a fresh empty deck

**Branch:** `fr-21-empty-deck-session`. **Requirements:** FR-20, FR-21.

A new session whose deck is empty never calls `finish()` (`SwipeViewModel.start()` only finishes when `reviewedBase > 0`), so its session row stays open. Decide the empty-deck behavior against §5.8, end the row, and update the spec if it doesn't already cover this.

- **Tests:** `FR-21` case in `SwipeViewModelSessionTest`.

## 3. 👤 Device checks

These need a physical iPhone and can't be done by a subagent.

- [ ] FR-16 crash fix: drag a video card partway and release, repeatedly, on a device build (fixed in `dadc5c3`).
- [ ] First Xcode Cloud build succeeds and reaches TestFlight as `0.1.<build number>`.
- [ ] Full run of [`docs/manual-test.md`](docs/manual-test.md), including the Layout and safe areas section.
- [ ] Fill in the `TODO(👤)` rows in [`docs/perf.md`](docs/perf.md) "Results" (NFR-1 to NFR-4, on a 10k+ and 50k library where listed).
- [ ] VoiceOver and Reduce Motion session on a device.

## 4. Conditional performance work

Do these only if a §3 measurement misses its target. File each as a WP when that happens. Details are in `docs/perf.md` "What to watch".

- Home count ladder: debounce `changes()` and cache per-bucket counts (NFR-4, post-delete hitches).
- AVPlayer churn: keep the player alive for the top cards (NFR-2).
- `VideoItemCache`: add a real LRU bound (NFR-3).
- `tileMetadata` re-fetch in `QueueViewModel` (queue-load spikes).

## 5. New features

Add each feature's behavior to `REQUIREMENTS.md` in the same branch as its code. Run WP-8.1 before WP-8.2.

### WP-8.1 Open the current item in Photos

**Branch:** `fr-32-open-in-photos`. **Requirements:** new FR-32, §5.8 Swipe.

A button on the swipe screen that takes the user to the current photo or video outside the deck. **Blocked on a decision:** iOS has no documented way to open one specific asset in the Photos app. The undocumented `photos-redirect://` scheme opens Photos but has been reported not to target an asset, and could break in any iOS release.

- Option A: open the Photos app with `photos-redirect://`, landing wherever Photos was last left.
- Option B: an in-app full-screen view with zoom, plus the system share sheet.
- Goes through `SystemActions` (hard rule 5). Opening must not record a decision or advance the deck (NFR-7).
- **Tests:** `FR-32` case showing the intent leaves the deck index and decisions unchanged.

### WP-8.2 Hide sensitive photos by default

**Branch:** `fr-33-hide-sensitive`. **Depends on:** WP-8.1. **Requirements:** new FR-33, FR-29, NFR-1, NFR-3, NFR-6; §1 non-goals.

A Settings toggle, on by default, that keeps sensitive items out of the swipe UI. **Blocked on a decision:** the detection method.

- Option A: Apple's on-device `SensitiveContentAnalysis`. Detects nudity only, needs the `com.apple.developer.sensitivecontentanalysis.client` entitlement, works only on a physical device, and reports nothing unless the user has turned on Sensitive Content Warning or Communication Safety in iOS Settings. The app must show when analysis is unavailable rather than imply items are being hidden.
- Option B: a bundled on-device Core ML classifier. No system setting needed and it can cover more categories, but it adds model size, licensing review, and false positives and misses.
- Either option changes the §1 "no ML" non-goal, which needs a spec edit. Analysis must stay on-device (NFR-6).
- Analysis needs pixels, so a whole-library scan is ruled out (hard rule 6). Analyse within the prefetch window and skip a flagged card before it is shown, without recording a decision. Measure against NFR-1.
- Define what happens to source counts, which cannot know about unscanned items.
- **Tests:** `FR-33` cases with a fake analyzer for on, off, flagged, and analysis unavailable.

### WP-8.3 Fix the empty Bursts deck

**Branch:** `fr-6-burst-deck`. **Requirements:** FR-6, FR-25, §6.5, §6.8.

The Bursts count and deck ids enumerate every frame with `includeAllBurstAssets = true` (`PhotoKitSourceFetches.kt:145`), but later id lookups pass nil options (`PhotoKitMediaLibrary.kt:241`, `:268`, `PhotoKitMediaLoader.kt:117`, `:197`, `VideoItemCache.kt:189`). Likely cause, not yet confirmed on a device: nil options leave out non-representative frames, so metadata comes back empty and `SwipeScreen` shows its empty state.

- Reproduce on a device with real bursts first. Whether `includeAllBurstAssets` applies to local-identifier fetches is not documented. If it doesn't, resolve frames another way, such as `fetchAssetsWithBurstIdentifier`.
- Use one shared lookup for all five call sites.
- **Check `delete` carefully.** It treats zero fetched assets as `Deleted` (`PhotoKitMediaLibrary.kt:271`), so a frame the lookup misses would be marked `DELETED` while staying in the library.
- Hard rule 1 still applies: `CommitDeletionsUseCase` remains the only caller.
- **Tests:** `FR-6` case for a deck whose metadata page is partly or wholly missing; `FR-25` case that an unresolved id isn't reported as deleted.

### WP-8.4 Show storage freed (nice to have)

**Branch:** `fr-34-storage-freed`. **Requirements:** new FR-34, FR-25, Risk R3, §2 open question.

Show the total size of the items being deleted on the deletion confirmation.

- Sizes come from `PHAssetResource` `fileSize`, which is undocumented KVC (R3). Sum on `Dispatchers.Default` for the queue only.
- If any size can't be read, leave the line out or mark it as partial. Never block a delete on it (R3).
- Wording must be honest: items stay in Recently Deleted for 30 days, and iCloud-only items may not free device space. For example, "About 1.2 GB. Space is freed after Recently Deleted clears."
- Update §2 and R3. Doesn't change the delete path (hard rule 1, NFR-5).
- **Tests:** `FR-34` cases for totals, an unreadable size, and an empty queue.

## Tracker

| WP | Title | Status |
| --- | --- | --- |
| 7.1 | Bitmap lifetime | [ ] |
| 7.2 | Undo vs in-flight decision | [ ] |
| 7.3 | Fresh empty deck session | [ ] |
| 8.1 | Open item in Photos | [ ] |
| 8.2 | Hide sensitive photos | [ ] |
| 8.3 | Fix empty Bursts deck | [ ] |
| 8.4 | Storage freed (nice to have) | [ ] |
