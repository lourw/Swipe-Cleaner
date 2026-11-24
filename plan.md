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
8. **Merged by the main agent.** The sub-agent works in its own worktree (`../swipe-app-wt/<branch>`) and does not merge. The main agent merges per AGENTS.md "Git workflow": `git merge --ff-only <branch>`, then `git worktree remove` and `git branch -d`. If `main` has moved on, it rebases, resolves conflicts, re-validates, then merges.
9. **Summary handed back.** The sub-agent reports: what was built, the test names added, any spec edits, any open 👤 checkpoints, and anything it deferred.

### Sub-agent brief template

When dispatching a WP, give the sub-agent:

> Implement **WP-X.Y** from `plan.md` in `/Users/lourw/personal/swipe-app`. Read `AGENTS.md`, the REQUIREMENTS sections the WP cites, and the WP itself before you start. Follow §0 "Global definition of done". Don't start other WPs. If the spec is ambiguous, use the defaults under REQUIREMENTS §2 "Open questions". If the spec and the code disagree, update the spec and say so. Report back using §0 item 9.

---

## 1. Waves

WPs in the same wave don't depend on each other. Up to 2 subagents run at once (AGENTS.md); pair WPs that also don't touch the same files.

- **Wave A:** WP-7.1, WP-7.2, WP-7.3
- **Wave B (only if device numbers call for it):** the conditional items in §4
- **Wave C:** WP-8.1, then WP-8.2
- **Wave D:** WP-8.3, WP-8.4
- **Wave E:** WP-8.5, then WP-8.6
- **Wave F:** WP-8.7
- **Wave G:** WP-8.11, after WP-8.1 merges
- **Wave H:** WP-7.4 (report-only, pairs with anything)
- **Wave I (architecture audit, 2026-11-19):** WP-7.5, WP-7.6, WP-7.7, then WP-9.1 to WP-9.9. See §6 for what each one conflicts with.

**Priority order (set by the user, 2026-11-19; updated the same day after the architecture audit).** When a slot frees up, take these first, in this order, ahead of every other queued WP. The dependency and conflict rules in AGENTS.md still apply.

1. **WP-8.10** Exclude albums from Shuffle. Top-priority feature. Starts with its deck-membership prerequisites.
2. **WP-7.5** Bind every decision to the card on screen. A confirmed way for kept photos to end up queued. Doesn't conflict with WP-8.10, so it can run beside it.
3. **WP-7.6** Report only the photos that were really deleted. Data-safety bug.
4. **WP-7.4** Audit swipe, queue, and delete. Report only; covers the paths WP-7.5 and WP-7.6 don't.
5. **WP-8.12** Stop Home counts flickering, and check every count. After WP-8.10.
6. **WP-9.3** One toast host, then **WP-8.9** Redesign the favorite delete confirmation.
7. **WP-8.3** Fix the empty Bursts deck. After WP-7.6.

Run **WP-9.1** and **WP-9.2** before WP-8.6 or WP-8.11 start, because both add to the swipe screen and the delete path.

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

### WP-7.4 Audit swipe, queue, and delete for kept items that end up queued

**Branch:** `fr-13-decision-audit`. **Requirements:** FR-9, FR-12 to FR-15, FR-20, FR-22, FR-24 to FR-28, NFR-5, NFR-7, hard rules 1 and 2. **Priority:** 2 (§1).

Reported 2026-11-19: a friend testing the app found many photos he meant to keep in the Review Queue. We don't know yet whether it's a bug, the gestures, or the UI. This WP finds out. **It is report-only:** it adds tests and a written report, and changes no production code. Fixes are filed as new WPs from its findings.

The 2026-11-19 architecture audit already confirmed two causes, now filed separately: a button tap during a swipe's fly-off records against the wrong card (**WP-7.5**), and a partly resolved delete batch is marked fully deleted (**WP-7.6**). Don't fix those here. Check that they're the only such paths.

Trace every path from a gesture or button to a `DELETE` row and from the queue to `MediaLibrary.delete`, and check that the id and verdict recorded always belong to the card the user saw. At minimum, check:

- **Wrong card.** A decision is written against the id of the card on screen, not whatever index the ViewModel has moved to. Look at fast consecutive swipes, a swipe while the next card is still promoting, and decisions made while a deck rebuild or a library change (`changes()`) reorders or shrinks the deck.
- **Wrong verdict.** Right swipe, the Keep button, and the VoiceOver Keep action always record `KEEP`. Check the drag-direction sign, RTL layout, rotation, and the fling-velocity path in `SwipeSpec.kt`. Check whether a mostly-vertical or short drag can resolve as a left swipe.
- **Undo.** Undo of a `KEEP` never leaves or creates a `DELETE`. Undo after a resumed session, after a failed write (NFR-7), and with the WP-7.2 in-flight guard.
- **Overwrites.** A later decision on the same id replaces the earlier one. A stale or retried write can't land after a newer one and turn a `KEEP` back into a `DELETE`.
- **Favorites (FR-22).** Confirming or dismissing a favorite confirmation can't record a `DELETE` for a different card.
- **Resume and decks (FR-9, FR-20).** A resumed session or a different deck never re-labels earlier decisions. The queue shows only ids whose latest decision is a pending `DELETE`.
- **Queue and delete (FR-24 to FR-28).** Restore records `KEEP` for the right id. The ids passed to `MediaLibrary.delete` are exactly the queued ids. Pruning and the `DELETED` marking can't touch kept items (see also the zero-fetched-assets note in WP-8.3).
- **UX causes.** Whether a nervous swipe, a short drag, or the button layout could plausibly queue items by accident. Note these as UX findings, separate from code bugs.

- **Tests:** add a `commonTest` for each suspected bug, named after its FR, with fakes and Turbine. A test that reproduces a real bug may fail; mark the report with its name rather than adding `@Ignore`. Tests for paths that turn out fine stay as regression tests and must pass.
- **Report:** `docs/audits/2026-11-decision-audit.md`, listing each path checked, the verdict (bug / risk / fine), the evidence (file:line and test name), and a suggested fix for each bug or risk. Keep the branch's failing tests out of `main`: if any test fails, the branch is not merged and its tests go to the fix WPs instead.
- 👤 Ask the friend which deck he used, whether he swiped or used the buttons, whether he used Undo, and whether the app was killed or restarted during the session.

### WP-7.5 Bind every decision to the card on screen

**Branch:** `fr-13-decision-card-binding`. **Requirements:** FR-12 to FR-15, FR-22, NFR-7, NFR-8, hard rule 2. **Priority:** 2 (§1). **Found by:** architecture audit, 2026-11-19 (confirmed in code).

A button tap (or a VoiceOver custom action) during a card's 220 ms fly-off records its verdict against the card that is leaving, not the next one. Example: the user swipes A right to keep it, then quickly taps Delete meaning B. A is recorded as DELETE, A's KEEP is dropped, and B is still showing. This is a likely cause of the kept-photos-in-the-queue report.

- Evidence: `SwipeScreen.kt:248-252` runs `commit(verdict)` after `cardState.flyOff(...)` even when `flyOff` returns early because a verdict is already committed (`SwipeCardState.kt:118`). `SwipeIntent.Keep/Delete/Skip` carry no id, so `SwipeViewModel.decide()` uses whatever `deck[index]` is (`SwipeViewModel.kt:316`). `DeckMotion.kt:89-92` `resetForNext()` can also cancel the first card's animation, which drops its commit.
- Make the decision intents carry the id the user acted on, e.g. `SwipeIntent.Decide(id, verdict)`. The ViewModel ignores a decision whose id isn't `deck[index]`. Apply the same to `ConfirmFavoriteDelete`. Update §6.6.
- `flyOff` reports whether it started. The button path commits only if it did. Action-bar buttons and custom actions do nothing unless the card is idle.
- A commit must never be lost by a later animation cancelling an earlier one.
- **Tests:** `FR-13` / `FR-12` ViewModel cases: a decision with a stale id is ignored, two different verdicts in quick succession each go to the right card. A UI test: swipe, then tap a button within the fly-off, and assert exactly one intent carrying the first card's id. Replace the `SwipeViewModelTest.kt:183` case that treats "both write asset-1" as correct.

### WP-7.6 Report only the photos that were really deleted

**Branch:** `fr-26-partial-delete`. **Requirements:** FR-25 to FR-28, FR-10, NFR-5, hard rule 1. **Priority:** 3 (§1). **Run before WP-8.3**, which edits the same functions. **Found by:** architecture audit, 2026-11-19 (confirmed in code).

`PhotoKitMediaLibrary.delete` deletes only the assets it can resolve, then returns a bare `DeleteResult.Deleted` (`PhotoKitMediaLibrary.kt:265-271`). `CommitDeletionsUseCase` then marks **every** requested id as DELETED and reports `ids.size` (`CommitDeletionsUseCase.kt:64-67`). An unresolved id (a burst frame, or an asset outside a limited selection) is marked DELETED while it stays in the library, inflates the success count and stats, and later reappears in decks.

- `DeleteResult.Deleted` carries the ids actually deleted (plus ids that were already gone, so the retry path in the KDoc still converges). Mark only those as DELETED. Leave the rest queued and say so on the success screen ("N deleted, M couldn't be found").
- `PruneQueueUseCase` (`PruneQueueUseCase.kt:31-43`) drops any queued id `items()` can't resolve, through the undo path with a fake `"prune"` session. Give it a dedicated `DecisionStore.dropFromQueue(ids)` that only removes rows whose decision is still DELETE (`DELETE … WHERE decision = 'DELETE' AND asset_id IN ?`), so it can never erase a KEEP that a restore just wrote.
- Hard rule 1 still holds: `CommitDeletionsUseCase` stays the only caller of `delete`.
- Make `FakeMediaLibrary.delete` able to resolve only some ids, matching the real implementation.
- **Tests:** `FR-26` cases for a partly resolved batch, a fully unresolved batch, and the retry-after-store-failure path. `FR-28` case that prune never removes a KEEP. A SQL test that `markDeleted` leaves KEEP rows and unqueued ids alone.
- 👤 On a device, with Limited Photos access, queue one photo from inside the selection and one that has since been removed from it, then delete.

### WP-7.7 Fix month deck order and test PhotoKit against its fake

**Branch:** `fr-7-month-order`. **Requirements:** FR-7, §6.5. **Found by:** architecture audit, 2026-11-19 (confirmed in code).

Month decks come out in PhotoKit's default order, not newest first as §6.5 says. `PhotoKitSourceFetches.kt:67-68` builds sorted options and then passes only `options.predicate` on, which drops the sort. `FakeMediaLibrary` sorts, so no test can catch it.

- Fetch with the full options.
- The fakes are tidier than PhotoKit: the fake sorts All, never fails id resolution, and deletes every id it's given. Add an `iosTest` contract suite that runs the same assertions against `PhotoKitMediaLibrary` on a seeded simulator and against `FakeMediaLibrary`: month order, All order, id resolution, and type filtering. (Burst and partial-delete cases join it in WP-8.3 and WP-7.6.)
- **Tests:** `FR-7` month-order case in the contract suite.

## 3. 👤 Device checks

These need a physical iPhone and can't be done by a subagent.

- [ ] FR-16 crash fix: drag a video card partway and release, repeatedly, on a device build (fixed in `dadc5c3`).
- [ ] First Xcode Cloud build succeeds and reaches TestFlight as `0.1.<build number>`.
- [ ] Full run of [`docs/manual-test.md`](docs/manual-test.md), including the Layout and safe areas section.
- [ ] Fill in the `TODO(👤)` rows in [`docs/perf.md`](docs/perf.md) "Results" (NFR-1 to NFR-4, on a 10k+ and 50k library where listed).
- [ ] VoiceOver and Reduce Motion session on a device.
- [ ] **Favorite confirmation user test (after WP-8.9 merges).** On a TestFlight build, have 2 or 3 people who haven't seen the app swipe a favorite left, without guidance. Note whether they can see which photo is being confirmed, whether they find Cancel without help, and whether anyone presses the wrong button. Record what you find under WP-8.9 and file follow-up changes as a new WP before calling the feature done.

## 4. Conditional performance work

Do these only if a §3 measurement misses its target. File each as a WP when that happens. Details are in `docs/perf.md` "What to watch".

- Home count ladder: debounce `changes()` and cache per-bucket counts (NFR-4, post-delete hitches).
- AVPlayer churn: keep the player alive for the top cards (NFR-2).
- `VideoItemCache`: moved to WP-9.4 (it's a correctness race, not only tuning).
- `tileMetadata` re-fetch in `QueueViewModel` (queue-load spikes).

## 5. New features

Add each feature's behavior to `REQUIREMENTS.md` in the same branch as its code. Run WP-8.1 before WP-8.2.

### WP-8.1 Share button on the swipe screen

**Branch:** `fr-32-share-button`. **Requirements:** new FR-32, §1 non-goals, §5.8 Swipe, NFR-7.

A share button on the swipe screen that opens the iOS share sheet (`UIActivityViewController`) for the current photo or video. Decided 2026-11-09: this replaces opening the item in the Photos app, which iOS has no documented way to do for one specific asset.

- Remove "sharing" from the §1 non-goals and add FR-32 and the button to §5.8. Icon and placement use `SwipeTheme` tokens (hard rule 3).
- Share the original media through a platform interface in `data/`, implemented in `iosMain/platform/` (hard rule 5). Videos and Live Photos must share as real media, not a still frame.
- Load the asset only when the button is tapped, and release any temp file afterwards. iCloud-only items need a loading state and a clear failure toast.
- Opening, completing, or cancelling the share sheet must not record a decision or advance the deck.
- **Tests:** `FR-32` cases showing share leaves the deck index and decisions unchanged, and that a load failure shows the toast. Use a fake share interface.
- 👤 On a device: share a photo, a video, and a Live Photo.

### WP-8.2 Hide sensitive photos by default

**Branch:** `fr-33-hide-sensitive`. **Depends on:** WP-8.1. **Requirements:** new FR-33, FR-29, NFR-1, NFR-3, NFR-6; §1 non-goals.

A Settings toggle, on by default, that keeps sensitive items out of the swipe UI.

**Decided (2026-11-09):**

- Detection uses Apple's on-device `SensitiveContentAnalysis`. It detects nudity only, needs the `com.apple.developer.sensitivecontentanalysis.client` entitlement, and works only on a physical device. It reports nothing unless the user has turned on Sensitive Content Warning or Communication Safety in iOS Settings, so the app must show when analysis is unavailable rather than imply items are being hidden.
- A flagged item is skipped silently: it never appears in the deck and no decision is recorded. It comes back when the toggle is turned off.

- This changes the §1 "no ML" non-goal, which needs a spec edit. Analysis must stay on-device (NFR-6).
- Analysis needs pixels, so a whole-library scan is ruled out (hard rule 6). Analyse within the prefetch window and skip a flagged card before it is shown, without recording a decision. Measure against NFR-1.
- Define what happens to source counts, which cannot know about unscanned items.
- **Tests:** `FR-33` cases with a fake analyzer for on, off, flagged, and analysis unavailable.

### WP-8.3 Fix the empty Bursts deck

**Branch:** `fr-6-burst-deck`. **Requirements:** FR-6, FR-9, FR-21, FR-25, §5.8, §6.5, §6.6, §6.8. **Priority:** 7 (§1). **Run after WP-7.6**, which changes `delete` and prune.

Reported by the user on a device, 2026-11-10: opening the Bursts smart filter shows "no items", and they want the Bursts row on Home to show how many there are.

The Bursts count and deck ids enumerate every frame with `includeAllBurstAssets = true` (`PhotoKitSourceFetches.kt:145`), but later id lookups pass nil options (`PhotoKitMediaLibrary.kt:241`, `:268`, `PhotoKitMediaLoader.kt:117`, `:197`, `VideoItemCache.kt:189`). Likely cause, not yet confirmed on a device: nil options leave out non-representative frames, so metadata comes back empty and `SwipeScreen` shows its empty state.

- Reproduce on a device with real bursts first. Whether `includeAllBurstAssets` applies to local-identifier fetches is not documented. If it doesn't, resolve frames another way, such as `fetchAssetsWithBurstIdentifier`.
- Use one shared lookup for every single-asset id lookup. There are six, not five: WP-8.1 added `PhotoKitMediaSharer.kt:106`. `PruneQueueUseCase` also depends on it through `items()`.
- **An unresolved id must not end the deck (audit, 2026-11-19).** Today `cardAt()` returns null when the current id has no metadata (`SwipeViewModel.kt:558`), and `SwipeScreen.kt:125` shows "Nothing left here", so one asset deleted elsewhere strands the whole deck. Drop unresolved ids from the deck without recording a decision, and move on.
- **Add a loading state.** `SwipeUiState` has no loading flag, so every deck open briefly shows the empty state (`SwipeScreen.kt:90-92`). Add one, shown as a placeholder card, separate from "deck empty". Update §6.6 and §5.8.
- The Bursts row on Home shows a count, and that count matches the deck the row opens (FR-9). Default: count deck items (burst frames), like every other row. If the user wants the number of bursts instead, change FR-6 in the same branch.
- **Check `delete` carefully.** It treats zero fetched assets as `Deleted` (`PhotoKitMediaLibrary.kt:271`), so a frame the lookup misses would be marked `DELETED` while staying in the library.
- Hard rule 1 still applies: `CommitDeletionsUseCase` remains the only caller.
- **Tests:** `FR-6` case for a deck whose metadata page is partly or wholly missing, and for an id that goes missing mid-session; a loading-state case; burst cases in the WP-7.7 contract suite.

### WP-8.4 Show storage freed (nice to have)

**Branch:** `fr-34-storage-freed`. **Requirements:** new FR-34, FR-25, Risk R3, §2 open question.

Show the total size of the items being deleted on the deletion confirmation.

- Sizes come from `PHAssetResource` `fileSize`, which is undocumented KVC (R3). Sum on `Dispatchers.Default` for the queue only.
- If any size can't be read, leave the line out or mark it as partial. Never block a delete on it (R3).
- Wording must be honest: items stay in Recently Deleted for 30 days, and iCloud-only items may not free device space. For example, "About 1.2 GB. Space is freed after Recently Deleted clears."
- Update §2 and R3. Doesn't change the delete path (hard rule 1, NFR-5).
- **Tests:** `FR-34` cases for totals, an unreadable size, and an empty queue.

### WP-8.5 Show a card's place in its duplicate set

**Branch:** `fr-35-duplicate-set-label`. **Requirements:** new FR-35, FR-6, §5.8 Swipe, §6.5, §6.6, NFR-8.

In the Likely Duplicates deck, label each card with its place in the set, for example "2 of 4 in this set".

- `DuplicateGrouper.group` returns a flat id list, so groups have to be exposed to the deck and `CardState`. Keep `BuildDeckUseCase`'s deck contents and order, and Home's counts, unchanged.
- **Groups must include decided items.** §6.5 subtracts decided ids before grouping, so after a resume a set would look smaller than it is. Work out set membership from the unfiltered candidates, and use it only for the label and WP-8.6.
- Keep to metadata and the existing candidate scan (NFR-4, hard rule 6). The label uses `SwipeTheme` tokens and is read by VoiceOver.
- Other decks show no label.
- **Tests:** `FR-35` cases for the numbering, a resumed session with decided members, and non-duplicate decks.

### WP-8.6 Warn before queueing the last copy in a set

**Branch:** `fr-36-last-copy-warning`. **Depends on:** WP-8.5. **Requirements:** new FR-36, FR-22, NFR-5, NFR-7.

In the Likely Duplicates deck, a Delete on an item asks for confirmation first when every other member of its set is already queued for deletion or deleted.

- Follow the FR-22 favorite flow: nothing is written, the card returns, and a toast asks for confirmation. Generalize `pendingFavoriteDelete` rather than adding a parallel path, and cover both gestures and buttons (`decide`).
- Check against the stored decisions at swipe time, so undo and resumed sessions give the right answer. A kept or skipped member means no warning.
- A card that is both a favorite and the last copy gets one confirmation, not two.
- This adds a confirmation only. It doesn't change the delete path (hard rule 1).
- **Tests:** `FR-36` cases for last copy, a member kept or skipped, undo of an earlier delete, resume, favorite plus last copy, and accepting or dismissing the confirmation.

### WP-8.7 Remove the intro screen

**Branch:** `fr-1-remove-onboarding`. **Requirements:** FR-1, FR-2, FR-3, §5.8 screen map, §6.4.

Open straight to Home on first launch, with the iOS photo access prompt shown immediately instead of after an intro screen.

- `NOT_DETERMINED` currently routes to `OnboardingScreen`, whose button is the only thing that fires `RequestAccess` (`PermissionViewModel`). Request access automatically once on launch, and don't fire it again while a request is in flight.
- Home must render sensibly while access is undecided, without fetching the library or showing an error, then load once access is granted (NFR-4).
- Delete `OnboardingScreen`, the `Onboarding` route, and `onboardingSeen` from `SettingsStore` and its implementations, with their tests. Leaving a stale `NSUserDefaults` key on existing installs is fine.
- Keep `DeniedScreen` for `DENIED` (FR-3) and the limited-access banner (FR-2).
- Update FR-1, the §5.8 screen map, and §6.4 in the same branch. Check that the photo library usage string in `iosApp/Info.plist` clearly explains why access is needed, since nothing else will.
- Run on a reset simulator for Allow Full Access, Limit Access, and Don't Allow.
- **Tests:** `FR-1` cases in `PermissionViewModelTest` for auto-request once on `NOT_DETERMINED`, routing for each outcome, and no prompt for a returning user.

### WP-8.8 Center the media type selector

**Branch:** `fr-5-center-selector`. **Requirements:** FR-5, §5.7, §5.8 Home.

The All / Photos / Videos segments sit at the left of a full-width track on Home. Center them.

- `SegmentedControl` is a full-width `Row` with the default start arrangement, and its segments wrap their labels. Center the segments within the track in the shared component, and keep the 44 dp touch targets.
- Update §5.7 to say the segments are centered.
- **Tests:** `FR-5` layout case showing equal space on both sides of the segments.

### WP-8.9 Redesign the favorite delete confirmation

**Branch:** `fr-22-favorite-confirm-redesign`. **Requirements:** FR-22, FR-15, §5.3, §5.7, NFR-5, NFR-8. **Priority:** 6 (§1). **Depends on:** WP-9.3, whose toast host this builds on. **Run before WP-8.6**, which reuses this confirmation.

Reported by the user, 2026-11-10 and 2026-11-19. When a favorite is swiped left, the confirmation that appears has three problems:

1. **It looks unfinished.** The text is off-center and the toast treatment looks like an error. It needs a proper visual design.
2. **The buttons are poorly arranged.** It isn't obvious which action deletes and which keeps the photo.
3. **There's no way out.** The confirmation can't be dismissed, so users feel stuck. The only escape is pressing Delete and then Undo. They also can't see the photo they're confirming.

**What to build**

- **Design.** Replace the toast with a dedicated confirmation component: a compact card anchored at the bottom, above the action bar. It never covers or resizes the current photo, so the user can see what they're confirming. Title "Delete this favorite?" with one line of supporting text, both centered. Design it in `SwipeTheme` tokens only (hard rule 3). Add the component to §5.7 and update FR-22 and the §5.8 Swipe wireframe.
- **Buttons.** Two buttons side by side, the same size, each with a 44 dp target: **Cancel** on the left, **Delete** on the right, following the iOS alert convention. Cancel is the secondary style. Delete is the only button in the `delete` verdict color, since it records a delete verdict. No button is pre-selected, and nothing auto-accepts (NFR-5).
- **Backing out.** Cancel sends `SwipeIntent.DismissToast`, which already clears `pendingFavoriteDelete`. It records nothing, keeps the card on screen, and sends the card back to center. Dragging the card again or swiping it right also counts as cancel. Delete sends `ConfirmFavoriteDelete`, as today.
- **Lifetime.** The confirmation stays until the user acts or the deck moves on. Undo while it's showing closes it.
- **Accessibility (NFR-8).** VoiceOver announces the title when it appears and moves focus to it. Both buttons have labels ("Cancel, keep this photo", "Delete favorite"). Honour Reduce Motion for its entrance.
- This doesn't change the delete path (hard rule 1). The DELETE is still written only after Delete is pressed (hard rule 2).
- **Tests:** `FR-22` cases for Cancel recording nothing and keeping the card, Delete queueing the delete and advancing, a new drag or right swipe acting as cancel, Undo closing it, and the confirmation clearing when the deck moves. Run on the simulator and compare against the updated §5.8 wireframe (§8.4).
- 👤 Then the user test in §3 ("Favorite confirmation user test"). The WP is done only after its findings are dealt with.

### WP-8.10 Let users exclude albums from Shuffle

**Branch:** `fr-37-shuffle-album-exclusions`. **Requirements:** new FR-37, FR-4, FR-5, FR-8, FR-9, FR-20, §5.8, §6.5, NFR-4. **Priority:** 1, the top-priority feature (§1; confirmed by the user 2026-11-19).

**Prerequisites, in this branch, before the feature (audit, 2026-11-19).**

- **One deck-membership function.** "Source ids minus decided ids" is written three times: `BuildDeckUseCase.kt:41-53`, `LoadSourcesUseCase.kt:186-227`, and `AlbumsViewModel.kt:89-104`. Exclusions would need adding to all three, and FR-9 ("count equals deck") depends on them agreeing. Extract one domain function, e.g. `DeckMembership.ids(source, type, decided, excluded)`. `BuildDeckUseCase` orders its result, and the count paths take its size. WP-8.12 then builds on it.
- **A shuffle that survives library changes.** `BuildDeckUseCase.kt:52` shuffles the id list with a seeded `Random`, so adding or deleting any photo changes the whole permutation and a resumed Shuffle opens in a different order (FR-20). The real All fetch isn't sorted either (`PhotoKitMediaLibrary.kt:215`). Order by a stable per-id key, such as a hash of seed and id, so subtracting decided and excluded ids keeps the order. Test with an id added and an id removed between sessions.
- **Don't break saved sessions.** If this changes how `DeckSource` is serialized, an old session row will fail to decode in `SqlSessionStore.kt:68`, which today blocks every deck with `StartFailed`. Make a decode failure end that row and start fresh.

Shuffle everything draws from the whole library, including photos that are in albums. Let the user choose albums to leave out of Shuffle. Nothing is excluded by default.

- Add a per-album toggle on the Albums screen and persist the excluded album ids locally. Schema changes need a migration.
- Exclusion applies only to Shuffle. Opening an excluded album directly, By month, and smart filters still include its items.
- An item in any excluded album is left out of Shuffle, even if it is also in an album that isn't excluded.
- PhotoKit has no "not in album" predicate, so fetch the excluded albums' asset ids (ids only, hard rule 6) and subtract them. Subtract after the seeded shuffle, as decided ids are, so a resumed session keeps its order (FR-20). An album the user has since deleted is ignored.
- The Home Shuffle count must match the deck the row opens (FR-9) and must refresh when exclusions change or the library changes.
- Toggling mid-session affects the next deck or rebuild only. It must not move the card on screen.
- This doesn't remove anything from the deletion path. Deleting a Shuffle item still removes it from the library and every album.
- **Tests:** `FR-37` cases for exclusion on and off, an item in two albums, the Shuffle count, a resumed session, a deleted album, and the type filter (FR-5).

### WP-8.11 Hide button on the swipe screen

**Branch:** `fr-38-hide-button`. **Depends on:** WP-8.1, because both edit the swipe screen, `SwipeViewModel`, `SwipeIntent` / `ToastMessage`, and §5.7 / §5.8. **Requirements:** new FR-38, FR-15, FR-21, §5.2, §5.7 TopBar, §5.8 Swipe, §6.4, §6.6, NFR-7, NFR-8, hard rule 1.

A Hide button that hides the current item in iOS Photos, the same as tapping Hide in the Photos app. The item moves to the Photos Hidden album and leaves the deck. Decided 2026-11-09: the button goes in the swipe `TopBar`, immediately left of the queue (trash) button. Share keeps the right edge of the action bar.

- **Spec.** Add FR-38. FR-38 also states the existing behavior: hidden assets never appear in any deck or source count (PhotoKit's `includeHiddenAssets` defaults to false; nothing sets it). Update the §5.2 icon mapping (Hide = `visibility_off`), the §5.7 `TopBar` row, the §5.8 Swipe wireframe, `MediaLibrary` in §6.4, and `SwipeUiState` / `SwipeIntent` / `ToastMessage` in §6.6.
- **Look.** Transparent icon button with a 44 dp touch target, `textPrimary` icon, `SwipeTheme` tokens only (hard rule 3). It isn't a verdict, so it must not use the `keep` / `delete` / `skip` colors.
- **Hiding is immediate.** There's no queue and no in-app confirmation. Undo is the safety net. Add a separate `MediaHider` write-door interface in `data/` (per WP-9.1: never on `MediaLibrary`), implemented in `iosMain/platform/` with `performChanges { PHAssetChangeRequest(forAsset:).hidden = ... }` (hard rule 5). *(Amended 2026-11-24 at merge: the WP text's original `MediaLibrary.setHidden` contradicted WP-9.1; shipped as `MediaHider`.)*
- **Deletion safety (hard rule 1).** The hide path changes only `hidden` and never calls `deleteAssets`. Update the "only place that can ever call `performChanges`" comment in `PhotoKitMediaLibrary.kt` and `scripts/check-rules.sh`: `deleteAssets` stays reachable only from `CommitDeletionsUseCase`, and the hide change request is the only other permitted `performChanges` call.
- **Advance only after success (NFR-7, same rule as hard rule 2).** The deck advances only after the change succeeds. On failure the card stays and a toast says "Couldn't hide this item — try again". While a hide or a decision write is in flight, the button is disabled and extra taps are ignored.
- **No decision.** Hiding writes no KEEP / DELETE / SKIP row and doesn't count toward kept, queued, or skipped in the FR-21 summary. It does advance the progress count like any other card that leaves the deck. A pending FR-22 favorite confirm is cleared.
- **Undo (FR-15).** A hide pushes an entry onto the undo stack. Undo calls `setHidden(id, false)` and returns the card, fading and scaling it back in place, since it didn't leave by a side. Honour Reduce Motion. If unhiding fails, show a toast and keep the entry. After a hide, show a 3 s "Hidden in Photos" toast.
- **Accessibility (NFR-8).** VoiceOver label "Hide from library". Add a Hide custom action on the card.
- **Tests:** `FR-38` cases with `FakeMediaLibrary` for hide success advancing with no decision row, failure keeping the card and showing the toast, double-tap and in-flight guards, undo unhiding and returning the card, failed unhide, the summary counts, and the cleared favorite confirm.
- 👤 On a device: hide a photo and a video, check that they show up in Photos › Hidden, undo one, and check whether iOS shows its own confirmation prompt for the hide (record what happens in FR-38). **Specifically test undo after hide**: it depends on `fetchAssetsWithIdentifiers` returning hidden assets, which Apple's docs don't guarantee (2026-11-24 verifier advisory) — if undo always fails on device, that's why.

### WP-8.12 Stop Home counts flickering on a media type switch

**Branch:** `fr-5-count-flicker`. **Requirements:** FR-5, FR-9, NFR-4, §6.5 *Home counts*. **Priority:** 5 (§1). **Run after WP-8.10**, which also changes Home counts and adds the shared deck-membership function this WP uses.

Reported by the user on a device, 2026-11-10: switching between All, Photos, and Videos on Home makes a count jump to the total first and then settle on the filtered number.

- Reproduce first and find which number shows in between. Likely cause, not confirmed: `HomeViewModel` shows PhotoKit's raw count for the new type and then replaces it with the exact FR-9 count, or a result for the previous type lands after the switch.
- After a type switch, a row shows the placeholder until the count for the new type is ready. It never shows a number that belongs to another type or a number that is about to be replaced by a different one.
- Keep the NFR-4 rule that Home mounts without waiting for counts. If this changes the raw-then-exact behavior in §6.5, update the spec in the same branch.
- **Check that the counts are correct (user, 2026-11-19).** For every Home row (Shuffle, each smart filter, Albums, By month) and every type (All, Photos, Videos), the settled count equals the number of cards the row's deck opens with. Write down any row where it doesn't, fix it in this branch if the cause is in the count code, and otherwise file it as a new WP.
- **One count path (audit, 2026-11-19).** Home (`HomeViewModel.kt:196-243`), Albums (`AlbumsViewModel.kt:89-104`), and Months each compute counts their own way. Months appears to show raw `library.months()` counts that don't subtract decided items (suspected; check it). Put every count behind one `SourceCountsUseCase` built on WP-8.10's deck-membership function, and use it from all three screens.
- **Tests:** `FR-5` cases with Turbine that a type switch emits only the placeholder and then the final count, including when a slow result for the old type arrives late. `FR-9` cases, one per row and type, that the count equals the deck size with decided items present.
- Run on the simulator with a seeded library (docs/fixtures) that mixes photos and videos, and switch types quickly back and forth.

### WP-8.13 Make video sound work, with a mute button and a default setting

**Branch:** `fr-16-video-audio`. **Requirements:** FR-16, §5.6 (tap toggles mute), §6.5.

Reported by the user on a device, 2026-11-10: videos never play sound. FR-16 says videos start muted and a tap turns sound on, so either the tap doesn't unmute or the unmuted player is still silent.

- Reproduce on a device first, with the ring/silent switch in both positions. Likely cause, not confirmed: nothing sets an `AVAudioSession` category (no reference in `composeApp/src`), so playback uses the default category, which the silent switch mutes. The simulator has no silent switch, so it can't confirm this.
- If that is the cause, set a playback category when the user unmutes, behind an interface in `data/` with the implementation in `iosMain/platform/` (hard rule 5). Muted autoplay must not interrupt other apps' audio. Decide what happens to other audio when the user unmutes and record it in FR-16.
- **Mute button (user, 2026-11-10).** Add a visible mute button on video cards that shows the current state and toggles sound. Use `SwipeTheme` tokens and a 44 dp target, and add it to §5 in the same branch. Decided by the user 2026-11-10: the button is the only way to toggle sound, because tapping the video for sound isn't discoverable. Tapping a video no longer toggles mute; it toggles the chrome, as a tap on a photo does. Update FR-16 and the **Tap** line in §5.6 to match.
- **Setting (user, 2026-11-10).** Add a "Play video sound by default" setting next to the existing ones in `SettingsStore`. **Off is the default.** Off: every session starts muted, as FR-16 says today. On: sessions start with sound. The mute button still overrides it for the rest of the session. Update FR-16 and the settings section of the spec.
- **Tests:** `FR-16` cases that the button toggles mute, the state carries to the next video in the session, a new session starts from the setting (off and on), and the audio session is requested only when sound is on (fake).
- 👤 On a device: sound plays after unmuting with the silent switch on and off, music from another app keeps playing while videos are muted, and the setting takes effect on the next session.

### WP-8.14 Fix the Albums switch spacing and explain what it does

**Branch:** `fr-37-albums-switch-polish`. **Requirements:** FR-37, FR-8, §5.7 `SourceRow`, §5.8 Albums, NFR-8. **Conflicts with:** WP-9.6 (both edit `AlbumsScreen.kt`). Don't run them side by side.

Reported by the user on the simulator, 2026-11-23. The Albums screen's Exclude from Shuffle switch (WP-8.10) has two problems:

1. **It sits right against the count.** In `SourceRow.kt`, `SourceRowSwitch` puts the 51 dp `SwipeSwitch` track (`switchTrackWidth`) inside a box sized to `minTouchTarget` (44 dp). The track spills about 3.5 dp past each side of the box, and nothing separates the count from the switch, so the count and the track touch. The chevron gets only `xs` start padding.
2. **A new user can't tell what it's for.** Each row has a bare switch with no visible label. The only explanation is the VoiceOver description. "On" means "left out of Shuffle". That is the opposite of what most people expect a switch on an album to mean ("included"), and nothing on screen says so.

**What to build**

- **Spacing.** Make the switch's touch box at least as wide as the track (`max(minTouchTarget, switchTrackWidth)` or a new `ComponentSizes` token), keeping the ≥ 44 dp height. Put a spacing token (`s`) between the count and the switch, and between the switch and the chevron. Fix this in the shared `SourceRow`, so Home rows without a switch don't change. Tokens only (hard rule 3).
- **Explain it on the screen.** Add a short line of `footnote textSecondary` text under the Albums header, above the list: "Turn an album on to leave its photos out of Shuffle everything." Show it every time, not only on first launch. It costs one line and the app has no onboarding (WP-8.7 removed it).
- **Label the excluded rows.** When an album's switch is on, show "Not in Shuffle" under its title in `caption textSecondary`, so the state reads without having to interpret the switch. The row stays 64 dp. If the two-line layout doesn't fit, raise it in the WP report rather than changing the row height silently.
- **Spec.** Update the §5.7 `SourceRow` entry (switch box width, spacing, the excluded subtitle) and the §5.8 Albums text, and add a small Albums wireframe to §5.8 showing the explainer line and one excluded row. That wireframe was already listed as a 👤 follow-up on 8.10. FR-37's behavior doesn't change.
- **Accessibility (NFR-8).** Keep the existing switch description. Read the explainer as normal text before the first row. On an excluded row, include "not in Shuffle" in the row's description.
- **Tests:** a `FR-37` layout test showing the count's right edge and the switch's left edge don't overlap, with at least the `s` gap, for a 5-digit count and a long album title. A case that the subtitle appears only when the album is excluded. Run on the simulator and compare against the new §5.8 wireframe (§8.4).
- 👤 Look at Albums on the simulator with a few albums, one of them excluded, and confirm the spacing and that the explainer reads clearly.

### WP-8.15 Flip the Albums switch: on means "in Shuffle"

**Branch:** `fr-37-shuffle-switch-include`. **Requirements:** FR-37, §5.7 `SourceRow`, §5.8 Albums, NFR-8. **Depends on:** WP-8.14 (merged). **Conflicts with:** WP-9.6 (both edit `AlbumsScreen.kt`). Don't run them side by side.

Decided by the user, 2026-11-24, after seeing WP-8.14 on the simulator. The Albums switch is on when an album is **left out** of Shuffle. That's backwards from what people expect. Flip it: **on means the album's photos are in Shuffle, off means they're left out.** Every switch is on by default, because nothing is excluded by default.

- **Display only, no data change.** Keep storing excluded album ids in `shuffle_exclusion` (§7). The switch shows `checked = id !in excludedAlbumIds`, and toggling it still adds or removes the id. No schema change, no migration, and existing exclusions keep working: an album excluded before this change shows as off. Keep the intent name `ToggleShuffleExclusion` or rename it to match, the implementer's choice.
- **Copy.** Explainer line under the header: "Turn an album off to leave its photos out of Shuffle everything." Keep the "Not in Shuffle" subtitle, which now shows on rows whose switch is off.
- **Accessibility (NFR-8).** The switch label changes from "Exclude {album} from Shuffle" to "Include {album} in Shuffle", and its selected state follows the new meaning. VoiceOver then reads "on" for included, which matches the screen.
- **Chevron clipping (seen on the simulator, 2026-11-24).** Since 8.14, the chevron at the right of each Albums row is cut off. The wider switch box and the new gaps leave too little width for it. Fix it in the same branch, since it's the same row: the chevron must sit fully inside the gutter. Add a layout test asserting the chevron's right edge is within the row's end padding. Home rows without a switch mustn't change.
- **Spec.** Update FR-37 ("an **In Shuffle** switch, on by default; off leaves the album's items out of Shuffle everything"), the §5.7 `SourceRow` entry, and the §5.8 Albums text and wireframe (switches drawn on, one row off with "Not in Shuffle").
- **Tests:** update the existing `FR-37` UI and ViewModel tests to the new mapping. Add a case that a stored exclusion shows as off and the subtitle shows. Add a case that turning a switch off adds the id to the store, and turning it back on removes it. Add the chevron layout case. The deck and count tests shouldn't change. If any of them do, the change has leaked past the display.
- 👤 Look at Albums on the simulator: all switches on, turn one off, and check that the subtitle appears and the Home Shuffle count drops.

### WP-8.16 Make "Reset all decisions" visibly put photos back

**Branch:** `fr-30-reset-refresh`. **Requirements:** FR-30, FR-9, FR-10, §6.5 *Counts*, NFR-5. **Conflicts with:** WP-8.12 follow-ups and anything else editing `HomeViewModel.kt`. Don't run beside them.

Reported by the user on the simulator, 2026-11-24: after tapping Reset all decisions and confirming, nothing seemed to change, and the photos didn't come back to Shuffle.

**What the code does today (read 2026-11-24, not yet reproduced):**

1. **Reset does run, but only on KEEPs.** `SettingsViewModel.confirmReset` → `DecisionStore.clearKeeps` → `DELETE FROM decision WHERE decision = 'KEEP'`, as FR-30 says. Queued DELETEs stay in the queue and out of every deck, and already-deleted items are gone. In the user's library, 3 were kept and 13 were deleted or queued, so at most 3 photos could come back.
2. **Home never refreshes its counts after a decision change.** `HomeViewModel` re-runs `sourceCounts` only on `MediaLibrary.changes()`, on an FR-37 exclusion change, and on `SetTypeFilter` (`HomeViewModel.kt:80-112`). `decisions.stats()` updates the header, but the Shuffle, smart and album counts keep their old numbers. The photos are back in the deck (`BuildDeckUseCase` reads `decidedIds()` when it builds), but Home still shows the old count, so it looks as if nothing happened. The same staleness probably follows any swipe session. Check the Albums and By month ViewModels too.
3. **The dialog overpromises.** It says "so everything is undecided again", which isn't true for queued items.

**What to build**

- **Refresh counts on decision changes.** Re-run the count Flow when the decided set changes: after a reset, after a session ends, and after a commit or a queue edit. Use one trigger (e.g. a `DecisionStore` change Flow), deduplicated like the `changes()` and exclusion paths, so a row updates once per real change and never dips (§6.5 final-or-placeholder rule). Apply it to Albums and By month as well. Update §6.5 *Counts* with the new trigger.
- **Say what Reset does.** Keep FR-30's behavior: the delete queue stays untouched, which is the safe default (NFR-5). Change the dialog text to match it, e.g. "Photos you kept go back into your decks. Photos waiting to be deleted stay in the queue — remove them there to keep them." Show the number coming back ("3 kept photos will come back"). If that number is 0, say so in the dialog instead of offering a reset that does nothing. After the reset, the toast says how many came back. Update FR-30.
- **Optional, needs the user's call:** offer a second action that also empties the delete queue. Not built by default. Raise it in the WP report.
- **Tests:** `FR-30` cases for the Shuffle count rising by the cleared KEEPs after reset (fake store, Turbine), queued DELETEs staying out of the count and the deck, a resumed Shuffle session including the cleared ids after a rebuild (FR-20), the dialog count (0 and non-zero), and a `FR-9` case that counts refresh after a session records decisions.
- 👤 On the simulator: keep 2 photos, queue 1, reset. Check that Home's Shuffle count goes up by 2 straight away and the queued one stays queued.

## 6. Architecture follow-ups

From the 2026-11-19 architecture audit (two read-only reviews: data/domain/platform and presentation/UI). Each item was checked in the code unless marked *suspected*. These don't change behavior unless they say so, so most need no spec edit beyond §6. Several conflict with feature WPs; the "Run" line says how to order them.

What the audit found sound and worth keeping: the `InAppDeleteConfirmation` token, which makes delete safety structural; persist-before-advance with its stale-advance and in-flight guards; consistent throw-on-write and null-on-failed-read; `BitmapLruCache`; strict design-token discipline enforced by `check-rules.sh`; and broad FR-named tests on fakes, including the semantics audit.

### WP-9.1 Make the delete path a compile-time guarantee

**Branch:** `arch-media-deleter`. **Requirements:** hard rule 1, NFR-5, §6.4. **Run:** before WP-8.11, which adds a second write path.

Every ViewModel receives the whole `MediaLibrary`, `delete` included (`CommonModule.kt:68,86,95,106,116`). Hard rule 1 is enforced only by a text grep in `scripts/check-rules.sh:53-57`, which skips any file that never names `MediaLibrary`.

- Split out a `MediaDeleter` interface holding `delete` (WP-8.11's `setHidden` goes on a separate writer interface). Bind `MediaDeleter` only into `CommitDeletionsUseCase` in Koin. Update §6.4, AGENTS.md hard rule 1, and `check-rules.sh` to check the binding.
- **Tests:** `DiSmokeTest` still resolves everything. A test that no ViewModel's constructor takes a `MediaDeleter`.

### WP-9.2 Slim down SwipeViewModel and tidy sessions

**Branch:** `arch-swipe-session`. **Requirements:** FR-15, FR-20, FR-21, NFR-7, §6.6. **Run:** after WP-7.5 (same files), before WP-8.6 and WP-8.11. Conflicts with WP-9.3 and WP-8.9.

`SwipeViewModel` is 595 lines and runs session start/resume, the decision path, undo, metadata paging, prefetching, video preload, share, and the summary. WP-8.6, 8.11, and 8.13 all add to it.

- Move session start, resume, skip subtraction, reviewed-base maths, and the empty-deck end (`SwipeViewModel.kt:237-293`) into `StartSessionUseCase` and `FinishSessionUseCase` in domain.
- Extract plain, separately tested classes: `DeckCursor` (deck, index, metadata pages), `PrefetchWindow`, and `UndoStack`.
- Group `CardStack`'s 24 parameters and `DeckStage`'s 17 (`DeckMotion.kt:115-142`) into a few holders, such as `DeckCallbacks` and `VideoUiState`.
- **Session rows.** Opening a different source leaves the previous open session orphaned and its `session_skip` rows grow forever: end other open sessions when a new one starts. `updatePosition` writes on every swipe but is never read back: drop the write (and the column, with a migration). Match a resumable album session on album id only, so renaming an album doesn't break resume. If WP-8.10 hasn't already, make an undecodable session row end itself instead of blocking every deck (`SqlSessionStore.kt:68`).
- No behavior change. Existing `SwipeViewModel*Test` suites must pass unchanged, apart from moving to the new classes.

### WP-9.3 One toast host, placed the same everywhere

**Branch:** `arch-toast-host`. **Requirements:** §5.7 Toast, §6.6, FR-15, FR-22, NFR-7. **Run:** before WP-8.9. Conflicts with WP-9.2.

- **The card jumps.** The toast sits in a Column with the card (`SwipeScreen.kt:257-303`), so every "Undone" toast resizes the card, including during the undo return animation. The video scrubber does the same when switching between a photo and a video (`:401`). Overlay toasts in a box anchored above the action bar, and reserve the scrubber row's height on every card.
- **Three patterns.** Swipe keeps toasts in state; Queue and Settings send them as Channel events into local `remember` state (`QueueScreen.kt:42-50`, `SettingsScreen.kt:67-76`); `Toast.kt:41` keeps its own `dismissed` flag as a second source of truth. Placement differs too: Queue top, Settings bottom plus `xl`, Swipe above the bar. Make one `ToastHost`, one placement (bottom, per §5.7), and one pattern, and record the choice in §6.6.
- Queue shows the raw PhotoKit error text to the user (`QueueScreen.kt:47`). Show a plain message instead.
- **Tests:** a layout test that the card's size doesn't change when a toast appears or when the card type changes; toast cases per screen.

### WP-9.4 Make video preloading race-free

**Branch:** `nfr-3-video-cache-order`. **Requirements:** NFR-2, NFR-3, §6.5. Replaces the §4 "VideoItemCache LRU" item, which is a correctness issue, not just tuning.

- `VideoItemCache.kt:84,88` and `PhotoKitMediaLoader.kt:205` each `launch` on `Dispatchers.Default`, so a `cancel(id)` can run before its `preload(id)` and leave an `AVPlayerItem` cached forever, and a `stopCaching` can run before its `startCaching`. Run each on a serial dispatcher (`limitedParallelism(1)`) or an actor, and bound the cache to the prefetch window.
- *Suspected:* `cancel(id)` cancels a shared `Deferred` that a mounted player may be awaiting (`VideoItemCache.kt:97-98`), so the player keeps showing the poster. Don't cancel while something awaits it.
- **Tests:** ordering cases with a fake loader and a controlled dispatcher; the cache never exceeds the window.

### WP-9.5 Make the queue viewer safe to use

**Branch:** `fr-24-queue-viewer`. **Requirements:** FR-23 to FR-25, NFR-5, NFR-8, §5.8 Queue.

- *Suspected, very likely:* taps on the full-screen viewer fall through to the grid underneath, because `QueueItemViewer.kt:44-49` is a plain `Box` with only a background over the `LazyVerticalGrid`. Tapping the photo can open a different one. VoiceOver can reach the grid and "Delete N items" behind it. Make the viewer consume input and be its own traversal group, or put it in a full-screen dialog.
- The viewer shows only a still for videos, so a video can't be checked before deleting it. Play videos in the viewer.
- The viewer uses × for Close beside a check for Restore, while elsewhere × means Delete. Use a back or close icon that can't be read as Delete. Update §5.2.
- "Delete 1 items" (`QueueScreen.kt:128`, `:245`): use plurals.
- **Tests:** a UI test that tapping the viewer body doesn't change the open item; a semantics test that the grid isn't reachable while the viewer is open; plural cases.

### WP-9.6 Loading, empty, and error states for Albums and Months, and no double navigation

**Branch:** `fr-8-list-states`. **Requirements:** FR-7, FR-8, NFR-4, §5.8. Conflicts with WP-8.10 (Albums screen); run after it.

- `AlbumsUiState` and `MonthsUiState` are bare lists. A failed scan keeps the current rows (`MonthsViewModel.kt:84-95`), so the user sees an empty screen with no explanation, and the Months scan on a large library has no loading indicator. Add a status (loading, ready, empty, error) with retry, and add them to §5.8.
- *Suspected:* a double tap on a row pushes two Swipe screens on the same session, because the navigation collectors in `HomeDestinations.kt:64-72` and Albums/Months navigate on every event. Navigate only from a resumed back-stack entry.
- **Tests:** state cases for each ViewModel; a navigation test that a double tap pushes one route.

### WP-9.7 Settle the layering exceptions

**Branch:** `arch-layering`. **Requirements:** §6.1, AGENTS.md hard rule 5. Mostly docs plus small moves.

- `ui/` imports `data.MediaLoader`, `Haptics`, and `PerfTrace` directly (`SwipeScreen.kt:20-23`, `DeckMotion.kt:27-29`, `GridTile.kt`, `SourceRow.kt`, `QueueScreen.kt`), and `App.kt:298-302` and `HomeDestinations.kt:120` `koinInject` data interfaces into composables. Decide which are sanctioned UI services (image loading and haptics are reasonable), provide those through a `CompositionLocal`, write them into §6.1 and hard rule 5, and move the rest behind ViewModels.
- Haptics fire from the UI for swipes but from `QueueViewModel` for the batch delete. Pick one owner.
- `IosModule.kt:44-48` uses `runBlocking` to wait for the database driver, which can run on the main thread. Open the driver at startup or make first resolution asynchronous.
- **Tests:** `DiSmokeTest` still passes; `check-rules.sh` enforces whatever rule replaces the exceptions.

### WP-9.8 Gesture, accessibility, and cleanup polish

**Branch:** `polish-2026-11`. **Requirements:** §5.6, NFR-8, §5.7. Several items touch §5.6 behavior, so check each against the spec and update it where the code was right and the spec wrong (hard rule 4). Split this WP if it grows large.

- **Card image:** `DeckMotion.kt:305` uses `ContentScale.FillBounds` for §5.6 aspect-fill, which stretches photos. Use `ContentScale.Crop`.
- **Threshold haptic:** `SwipeCard.kt:351-355` fires only when entering a verdict and never resets below the threshold, so §5.6's "either direction" and re-crossing are missed.
- **Edge glow:** it appears only past the threshold, at full strength (`SwipeCard.kt:115`). Check §5.6 for a ramp.
- **Settings toggles:** they use `selected` with `Role.Switch` (`SettingsScreen.kt:191-194`), so VoiceOver says "selected" instead of on or off. Use `Modifier.toggleable`.
- **Scrubber:** no `setProgress` semantics action, so VoiceOver can't seek (`VideoScrubber.kt:45-50`).
- **Queue badge:** `QueueBadge` adds its own description inside the already-labelled TopBar button, so the count is read twice.
- **Leftovers:** move `DeniedScreen` and its test from `ui/screens/onboarding` to `ui/screens/permission`; fix the stale header in `Routes.kt:1-5`; decode `sourceJson` defensively in `App.kt:270`, like `typeFilter`.
- *Suspected:* `LaunchedEffect(revertToken)` in `DeckMotion.kt:82` replays a card return when the deck re-enters composition. Key it so a token is consumed once.
- **Minor:** Home, Albums, and Months wrap every intent in `viewModelScope.launch` without needing to. Move the debug gallery and swipe demo (about 380 lines, duplicating the action bar) out of release builds. Shadow uses `Color.Black` (`SwipeCard.kt:141`): add a token. Merge `VERDICT_TINT_ALPHA` and `tintedFillAlpha`, which name the same 16% tint.
- **Tests:** a case for each behavior change, named after its FR or NFR.

### WP-9.9 Cut redundant library scans

**Branch:** `nfr-4-scan-dedupe`. **Requirements:** NFR-4. Low priority; take it if the §3 perf numbers show refresh hitches.

- Each `changes()` refresh pays two full-library passes each for Bursts and Likely Duplicates: `smartCounts` (`PhotoKitMediaLibrary.kt:184,191`), then `refineSmart` (`LoadSourcesUseCase.kt:154`). Return candidates and burst ids from one pass, or cache them per refresh.
- `PermissionViewModel.kt:111` lists every id just to count the limited selection. Use the fetch result's count.
- The top-view-controller lookup is written twice and differs: `IosSystemActions.kt` doesn't follow `presentedViewController`, so the limited-library picker can fail to appear while a sheet is up; `PhotoKitMediaSharer.kt:172-183` does. Share one helper.

### WP-9.10 Resolve Undo vs. the fly-off window

**Branch:** `fr-15-undo-flyoff`. **Requirements:** FR-15, §5.6, NFR-7. Split out of WP-9.2 (2026-11-24): the implementer recorded a reasoned no-implement — only the composable sees the 220 ms fly-off window, so ignoring Undo until the commit lands needs a new UI→ViewModel protocol, which is a real behavior change deserving its own WP. `SwipeViewModelDecisionAuditTest` documents the current safe-but-imperfect behavior (Undo tapped mid-fly-off is silently dropped; the decision still persists). Decide the UX (ignore vs. queue the Undo) with the user, implement, and update §5.6/FR-15 in the same branch.

## Tracker

The main agent keeps this section current so nobody loses track of running subagents, including after a restart or context compaction. Rules are in AGENTS.md "Working with subagents". Subagents never edit this section.

### Active subagents

At most 2 rows. Add a row when you launch a subagent, before doing anything else. Remove it when the subagent reports back, after updating the WP's row below.

| Slot | WP | Role | Branch (worktree in `../swipe-app-wt/`) | Session ID | Started | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 9.10 | verify | `fr-15-undo-flyoff` | ses_df0e2d309ffeuh8AQjor5T0rBz | 2026-11-24 | Re-review of fix commits `4309f8c` + `3e4a97e` (hold window = release→dispatch via `flightToken`; 604/604). `--full` gate **PASSED** at `3e4a97e` (wp910-gate3.log). Merge on PASS |
| 2 | — | — | — | — | — | (empty. ⚠️ An agent outside this session is actively committing to `fr-36-last-copy-warning` (WP-8.6) — 3 commits at 14:57–15:29, worktree dirty until it goes quiet. Do not touch that worktree; verify independently once quiet) |

**Role** is `implement`, `verify`, or `fix`. **Session ID** is the `sessionID` the `subagent` tool returned. Pass it back as `sessionID` to continue that conversation.

### Work packages

**Status** is one of: `queued` → `implementing` → `verifying` → `fixing` (back to `verifying`) → `ready to merge` → `merged`. Use `blocked` for anything waiting on something outside the queue (a 👤 checkpoint, a user decision, a failed merge), and say why in Notes.

| WP | Title | Status | Branch | Notes |
| --- | --- | --- | --- | --- |
| 7.1 | Bitmap lifetime | merged | `nfr-3-bitmap-lifetime` | |
| 7.2 | Undo vs in-flight decision | merged | `fr-15-undo-decide-race` | |
| 7.3 | Fresh empty deck session | merged | `fr-21-empty-deck-session` | Empty deck at open: show empty state, end row, no summary |
| 7.4 | Decision and delete audit | merged | `fr-13-decision-audit` | Verified PASS; report at docs/audits/2026-11-decision-audit.md; 11 regression tests. Follow-ups: R1 fetch-error fix → noted on 8.3; U3 toast modality → 8.9; U4 undo fly-off → 9.2; U1/U2 diagonal-drag geometry → 👤 only after friend confirms his swipe style |
| 7.5 | Bind decisions to the card on screen | merged | `fr-13-decision-card-binding` | Verified PASS 2026-11-23 (validate.sh + fresh 370-test run). Follow-up nit filed to 9.2: Undo tapped during the 220 ms fly-off silently drops that swipe (no corruption; card is re-presented) |
| 7.6 | Partial delete marked as full | merged | `fr-26-partial-delete` | Verified PASS 2026-11-23; 2 review nits fixed pre-merge. 👤 device check (Limited access): queue one in-selection + one removed-from-selection photo, delete → "1 deleted, 1 couldn't be found", second stays queued. Risk filed to 7.4/8.3: nil fetch-error in the zero-fetch short-circuit |
| 7.7 | Month order and PhotoKit contract tests | merged | `fr-7-month-order` | Verified then FAIL(blocker)→fixed→PASS 2026-11-23: month+All fetches pass full sorted options; blocker (contract date-check depended on user-library content) fixed by filtering to fixture ids, + partial-write cleanup, dayOf bounds, §6.5/BuildDeck rationale. 433 tests, --full PASS after rebase onto 9.3. Real PhotoKit runner skips (no Gradle-host auth) w/ printed reasons. Merged at 472cf3f. 👤 granted-host run to exercise the real runner |
| 8.1 | Share button | merged | | 👤 manual share checks (FR-32) |
| 8.2 | Hide sensitive photos | queued | | |
| 8.3 | Fix empty Bursts deck | merged | `fr-6-burst-deck` | Verified PASS (no blockers, 5 nits) 2026-11-24 — verifier re-ran --full itself (485 tests) and confirmed hard rule 1 + drop/undo/page-retry logic. Nits folded pre-merge (verifiedGone requires an empty probe; honest comments; import cleanup; report fixes). Merged at 4c13339. Root cause: six single-asset lookups passed nil options (one burst representative) while the deck lists every frame. 👤: real-burst device checks (incl. undocumented includeAllBurstAssets on id fetches; fallback documented in code) |
| 8.4 | Storage freed (nice to have) | **merged** | `fr-34-storage-freed` | Merged 2026-11-24 at `0498c65` (verifier PASS all items — R3 traps: KVC throw-guarded, delete never gated on estimate, stale-queue race fixed). Main `--full` gate **PASSED** on the merged tree (597/0, XML 09:45); the earlier one-off Release failure was SIGTERM fallout. 👤: KVC `fileSize` on real assets, iCloud-only Partial/Unavailable |
| 8.5 | Duplicate set label | **merged** | `fr-35-duplicate-set-label` | Merged 2026-11-24 at `d2b09e1` (verifier PASS 574/0; memberships from UNFILTERED candidates trap pinned; 👤-checkpoint nit folded pre-merge). Main `--full` gate PASSED post-merge. 👤: label placement vs §5.8 wireframe + VoiceOver line |
| 8.6 | Last-copy warning | implementing | `fr-36-last-copy-warning` | ⚠️ Branch advanced by an agent outside this session (spec + data + presentation/ui commits `3b27c5f`/`7781c90`/`3117ba9`, following plan.md's "check stored decisions at swipe time" — no new table). My own implementer stood down on the collision. On quiet: independent verifier, rebase onto post-9.10 main, gate, merge |
| 8.7 | Remove intro screen | merged | `fr-1-remove-onboarding` | 👤 reset-simulator permission checks (manual-test Permissions 1–4) |
| 8.8 | Center media type selector | merged | `fr-5-center-selector` | 👤 Home visual glance vs §5.8 wireframe |
| 8.9 | Favorite confirmation redesign | merged | `fr-22-favorite-confirm-redesign` | Verified PASS-with-nits 2026-11-23; nits folded pre-merge (FR-22 spec: a completed left swipe after drag-cancel **re-raises** the confirm; §5.7 Toast row no longer describes a confirm-toast). `FavoriteConfirmCard` (bottom card above action bar, Cancel/Delete ≥44 dp, 150 ms fade, live-region announce), U3 drag-to-cancel. Merged at 617805e after rebase onto 7.7 + `--full`. Deviation: no forced VO focus (CMP limit). Report at docs/reports/2026-11-wp-8.9-favorite-confirm.md. 👤 §3 user test (2–3 people, TestFlight) — WP not fully done until findings dealt with |
| 8.10 | Exclude albums from Shuffle | merged | `fr-37-shuffle-album-exclusions` | Priority 1; verified PASS 2026-11-23; merged after clean rebase onto 7.6 + combined `--full` pass. Verify nits filed: mid-session guarantee untested + duplicate homeViewModel test helper → 9.x cleanup; §5.8 Albums wireframe → 👤 may want one |
| 8.11 | Hide button | **merged** | `fr-38-hide-button` | Merged 2026-11-24 at `570df28` (verifier PASS on all 7 items with fresh `--rerun-tasks` evidence, 544/0; clean rebase; post-rebase `--full` PASSED; second rebase was docs-only drift so not re-validated). F1–F4 all fixed & pinned. Advisories live in WP text: 👤 device pass must specifically test **undo after hide** (undocumented PhotoKit identifier-fetch dependency); failed-hide drops FR-22 confirm (candidate §6.6 sentence). 👤 also: Limited vs Full permission prompt on first hide |
| 8.12 | Home count flicker and correctness | merged | `fr-5-count-flicker` | Priority 5; verified PASS 2026-11-23 (404 tests, every count path traced); merged at c727a6f with doc fixups. SourceCountsUseCase is the single count path, LoadSourcesUseCase deleted. Deferred: smartCounts removal + albumRows render → 9.9; 👤 simulator quick-switch check after merge |
| 8.13 | Video sound, mute button, default-sound setting | queued | | Reported by user 2026-11-10; reproduce on a device first. Setting defaults to off |
| 8.14 | Albums switch spacing and explainer | merged | `fr-37-albums-switch-polish` | Verified PASS (no blockers) 2026-11-24: spacing math, Home rows pixel-identical, discriminating tests, spec=code, own validate rerun (469 tests). Nits accepted (chevron ~4 dp shift = pre-existing pattern, 👤 glance; explainer copy pinned via constant). Merged at 0501bba after clean rebase onto 8.3. Closes 8.10's §5.8 Albums wireframe follow-up. 👤: Albums glance on simulator. Deferred: 9.6 conflict noted |
| 8.15 | Flip Albums switch to "in Shuffle", fix chevron clip | queued | | Decided by user 2026-11-24: switch on = included, off = excluded; display-only flip, no schema change. Also fixes the chevron clipped since 8.14. Conflicts with 9.6 (`AlbumsScreen.kt`) |
| 8.16 | Reset all decisions: refresh counts, honest copy | queued | | Reported by user 2026-11-24 on simulator. Likely cause: Home/Albums/Months counts never re-run on decision changes; reset only clears KEEPs by FR-30 (queue untouched) and the dialog overpromises. 👤 optional "also clear queue" action needs user call |
| 9.1 | MediaDeleter split | merged | `arch-media-deleter` | Verified PASS (no blockers, 6 nits) — nits folded pre-merge (guard 5b: only `MediaDeleter.kt`/`PhotoKitMediaLibrary.kt` may declare `fun delete(`; `di/` path anchored; §6.7/NFR-5 wording precision). Merged at `b1f0080` (490 tests). NFR-5 graph test lives in `iosTest/di/DiSmokeTest.kt` (`NFR-5 no view model resolves MediaDeleter…`, CountingMediaDeleter) — verifier report cited a filename that doesn't exist; content verified present. Accepted nits: N2 (test hardcodes 7 ViewModel names vs enumerating Koin registry), N5 (`single { PhotoKitMediaLibrary() }` keeps the concrete type Koin-resolvable) → 9.x cleanup candidates. Unblocks 8.11 (still waits on 9.2) |
| 9.2 | Slim SwipeViewModel, tidy sessions | verifying | `arch-swipe-session` | Impl done: UndoStack/PrefetchWindow/DeckCursor extracted, Start/FinishSession use cases, session-row tidy (end-other-open, `position` column dropped via `2.sqm`, album-id-only resume, undecodable self-end confirmed from 8.10), CardStack 26→10 + DeckStage 16→13 params. 524 tests (+34), --full PASS at base. Fly-off-Undo: not implemented — reasoned no-implement (needs UI→VM protocol for the 220 ms window), queued as follow-up. 👤 migration `2.sqm` resume check on installed builds |
| 9.3 | One toast host | merged | `arch-toast-host` | Verified PASS-with-nits 2026-11-23 (418 tests; nits 1–2+4 fixed pre-merge); merged at 31a9917 after `--full`. One ToastHost owns the only 3 s fuse + placement; deck toast is an overlay (card no longer jumps); Queue shows plain delete-error copy. Nits 3 (overlay over CTAs) + 5 (re-raise doesn't restart fuse) → 👤 device checkpoint / conscious choice. Unblocked 8.9 (merged) and 8.6 |
| 9.4 | Race-free video preloading | **merged** | `nfr-3-video-cache-order` | Merged 2026-11-24 at `4371344` (verifier PASS all 7, fresh 553/0; clean rebase onto 8.11; post-rebase `--full` PASSED; final rebase was docs-only drift). Defect was real (shared `Deferred` cancelled under mounted player). Cosmetics recorded for later: §6.5 "evicts once the load lands" wording off-by-a-hair; `UIScreen.mainScreen` lazy init may happen off-main (low risk); throwing loader caches failed entry (pre-existing). 👤: playback-start ≤1.5 s device check (`perf.md`) |
| 9.5 | Queue viewer safety | merged | `fr-24-queue-viewer` | Verified PASS (no blockers) 2026-11-24: verifier re-ran the suite fresh (465 tests) and confirmed the tap test fails against old code. Nits folded pre-merge (slot-convention comment, report SHA). Merged at adf3f75. 👤 VoiceOver + device checkpoints in report. Deferred: viewer mute control + scrubber + `autoplayVideos` setting (product call; 8.13 territory); scroll-block claim untested (behavior verified by hit-testing) |
| 9.6 | Albums/Months states, double navigation | **merged** | `fr-8-list-states` | Merged 2026-11-24 at `a46ccbe` (ff after clean rebase; independent verifier PASS on all 7 items; `--full` green pre- and post-rebase, 509 tests). Root cause of the failing FR-8 test was the test helper (route decoder matched every entry), not the app. Non-blocking notes kept: failed refresh after Empty keeps Empty (code-documented), horizontal centering not bounds-pinned. 👤: simulator glance of the four list states + VoiceOver announcement |
| 9.7 | Layering exceptions | queued | | |
| 9.8 | Gesture, accessibility, cleanup polish | queued | | |
| 9.9 | Redundant library scans | queued | | Low; only if perf numbers call for it |
| 9.10 | Undo vs. fly-off window | verifying | `fr-15-undo-flyoff` | Split from 9.2 (deliberate no-implement, see report). 👤 UX decision 2026-11-24: **queue the Undo**. Verifier REVISE (2 blocking: stale held-Undo across aborts; unforwarded hold) fixed: hold window is release→dispatch, keyed per flight via `SwipeCardState.flightToken` (`4309f8c`, `3e4a97e`), 3 new UI regression tests, 604/604, `--full` gate PASSED. 👤 device-check candidates in report |
