# WP-7.4 — Decision-integrity audit: kept items that end up queued

Date: 2026-11-23. Branch: `fr-13-decision-audit`. Report only — no production code changed.
All new tests live in `composeApp/src/commonTest/` and pass on `iosSimulatorArm64Test`.

## Scope and method

Question: is there any path where a photo the user **kept** (or never verded) ends up with a
`DELETE` row, or where a `DELETE` row is written for the **wrong card**?

Method: traced every path from gesture/button to a `Decisions` row and from the queue to
`MediaLibrary.delete`, then reproduced each suspect path as a commonTest against the fakes
(one test per suspect outcome). WP-7.5 (`Decide(id, verdict)` binding) and WP-7.6
(partial-delete reporting) are already merged; this audit verifies no **other** wrong-card /
wrong-verdict paths exist rather than re-checking those two.

## Verdict summary

| # | Path | Verdict |
|---|------|---------|
| 1 | Wrong card (tap vs card, races, stale ids) | **fine** |
| 2 | Wrong verdict (drag sign, RTL, fling, short/vertical drags) | **fine** (2 UX findings, U1/U2) |
| 3 | Undo (KEEP undo, resumed session, failed write, in-flight guard) | **fine** (known fly-off nit documented, U4) |
| 4 | Overwrites (stale/retried write flipping KEEP→DELETE) | **fine** |
| 5 | FR-22 favorite confirm/dismiss vs a different card | **fine** at VM level (U3: confirm is not modal) |
| 6 | Resume / decks (FR-9, FR-20) | **fine** |
| 7 | Queue → delete (FR-24–28) | **1 risk: R1** zero-fetch delete short-circuit; **R2** prune note |
| 8 | UX findings | U1–U4 below |

No reproduced path writes a `DELETE` row for a kept or never-verded card at the app layer.
The only way a kept photo can end up durably "queued but alive" is **R1**, a PhotoKit fetch
failure inside `delete` — filed below with a suggested fix, not fixed here.

## 1. Wrong card — fine

- Every decision funnels through `SwipeViewModel.decide()`
  (`presentation/swipe/SwipeViewModel.kt`), which drops any intent whose `id` is not the card
  at the deck cursor (`deck.getOrNull(index) != id`) and re-checks the cursor after the store
  write, dropping the advance when a decision for another card landed first.
- The gesture path reads its dismiss callback **at release time**
  (`ui/components/swipe/SwipeCard.kt:378-381`), and the commit lambda binds the verdict to
  the `cardId` captured when the gesture ends (`rememberCommit`, `ui/screens/swipe/SwipeScreen.kt:216-220`,
  via `SwipeVerdict.toIntent(id)`, `DeckMotion.kt:365`), so a card that changed identity
  mid-drag cannot be verded under a stale id — and even if a stale commit did reach the VM,
  its id guard rejects it.
- The deck is never rebuilt mid-session (no `changes()` collection in `SwipeViewModel`), so
  cursor indices cannot shift under a pending write.
- Tests: existing `FR-13 a decision naming a card that is not on screen is ignored`,
  `FR-13 rapid double tap writes once and never skips an undecided card`,
  `FR-12 two quick verdicts for different cards each land on the right card` (WP-7.5), and new
  `FR-13 integration out of order decision intents are all dropped and nothing is recorded`.

## 2. Wrong verdict — fine, with two geometry UX findings

- **Keep button, Delete button, Skip button, and the VoiceOver actions all funnel through
  one mapping**: `SwipeVerdict.toIntent(cardId)` at `SwipeScreen.kt:220`, with `cardId`
  resolved from the state at commit time, so a button cannot name a verdict for a card it
  does not show. Existing: `FR-12 Keep button flies the card off then sends the Keep intent`,
  `FR-13 Delete button commits with the medium impact`, `FR-14 Skip button sends the Skip
  intent`, and `NFR-8 swipe card exposes keep delete skip accessibility actions`
  (`SwipeScreenTest`, `SwipeCardTest`) — each asserts the exact intent recorded.
- Thresholds and fling rules are unit-tested against spec §5.6 in `SwipeMathTest`; new `ui/components/swipe/SwipeMathAuditTest` pins the
  remaining checklist cases (short drag below threshold, short **vertical** drag cannot
  resolve left, flick-up = SKIP, sign conventions).
- **RTL: fine.** There is no `LayoutDirection`/`contentDirection` handling anywhere in
  `commonMain` — the app is LTR-only, so drag-offset signs cannot flip. If RTL is ever added,
  `SwipeMath`'s sign assumptions must be revisited.
- **Rotation: fine.** The app is portrait-only in v1 (REQUIREMENTS §6.8), so a mid-drag
  rotation cannot occur; even if it could, offsets are local to the card and the verdict is
  bound to the id at release, so it could not be misattributed.
- Diagonal drags resolve by the rules below; both are spec-conformant but feel wrong to users
  (U1, U2 in UX findings).

## 3. Undo — fine; fly-off nit documented, not fixed

At the ViewModel level undo is remove-then-rewind, and a KEEP undo provably cannot leave a row:

- `FR-15 undo of a KEEP leaves no decision row for the card` — KEEP, undo, then delete the
  re-presented card: exactly one row exists and it is the new DELETE. The old KEEP row is gone
  (store `record` is an upsert, so row count is the discriminator).
- `FR-15 undo before a committed swipe decide arrives leaves no delete row and re-presents
  the card` — the fly-off race at VM level: the Undo is processed first (pops the previous
  card's step and removes its row), then the committed swipe's late `Decide` names a card
  that is no longer at the deck head and is dropped by the FR-13 guard. No DELETE row is
  written, nothing is stranded, the card is re-presented.
- `FR-15 nothing is undone after a failed write because the decision never reached the stack`
  — the failed KEEP (SaveFailed toast, card stays, the `NFR-7 failed write does not advance
  and reverts card` behavior) leaves an empty stack; Undo removes nothing; the next
  successful decide undoes cleanly (exactly one removal).
- Empty-stack Undo is covered by existing `FR-15 undo with an empty stack is a no-op`, and
  the WP-7.2 in-flight guards by `FR-15 undo is ignored while a decision write is in flight`
  and `FR-15 an undo dropped mid-write is not queued behind it`.
- `FR-15 undo after resume reports nothing to undo and records nothing` — the undo stack is
  in-memory and correctly empty after resume (FR-20); the old session's rows are untouched.

**Fly-off nit (U4), verified not fixed:** during the 220 ms fly-off (`SwipeSpec.FLY_OUT_MS`)
the card is already committed. The action-bar buttons are gated (`SwipeScreen.kt` `buttonCommit`
commits only when `SwipeCardState.flyOff` reports it started, which it refuses unless the card is
idle; existing test `FR-13 a button tap during a card's fly-off decides nothing`),
but the card itself is still draggable, and an Undo tap pops the *previous* entry while the
committed swipe's `Decide` intent is in flight — the VM then drops that Decide by the id guard.
Net effect: that one swipe is silently discarded, the card returns to the top, **no DELETE row
is written and nothing is stranded** (documented by the second test above). Polish follow-up
proposed below; per the orchestrator's brief this nit is documented, not fixed.

## 4. Overwrites — fine

- A card's only writers are `record` (upsert, one row per asset) and the guarded queue SQL;
  there is no partial update that could flip `decision` without a full upsert.
- Undo removes the row *before* rewinding the cursor, so a re-decide after undo cannot race a
  still-live old row (new `FR-15 undo of a KEEP leaves no decision row for the card` —
  after KEEP, undo, delete, the asset's only row is the new DELETE; store-level removal is
  `FR-15 remove deletes a decision and a session skip`).
- A queued DELETE is only ever replaced by an explicit re-verdict on the same asset (the
  restore upsert): existing `FR-24 restore removes item from queue and records KEEP`; and
  the queue SQL cannot touch kept rows: `FR-26 markDeleted leaves KEEP rows and unqueued
  ids alone`, `FR-28 dropFromQueue never removes a KEEP` (`SqlStoresTest`, iosTest).

## 5. FR-22 favorite confirmation — fine at the VM level

- `ConfirmFavoriteDelete` requires `intent.id == pendingFavoriteDelete` **and** that the
  pending id is the current card; a wrong id is a no-op and the dialog stays up
  (new `FR-22 confirming with the wrong id is ignored and keeps the dialog and the card`).
- The dismiss path records nothing (existing `FR-22 dismissing favorite confirm queues nothing`,
  and `FR-22 a second confirm during the in-flight write queues nothing extra`).
- A swipe of the card while the dialog is up can only record KEEP or SKIP — the guarded
  confirm can never see a different card, so dismissing/confirming cannot DELETE a different
  card than the one shown. New `FR-22 right swipe while the favorite confirm is up records KEEP not DELETE`.
- UX: the confirm is a toast overlay — the card underneath stays fully draggable and the
  toast itself has no dismiss (that is WP-8.9's "no way out" complaint). Safe (guarded
  confirm), but the card can be swiped away under it (U3); add gesture-gating to WP-8.9.

## 6. Resume / decks (FR-9, FR-20) — fine

- Each entry into the swipe screen starts (or resumes) exactly one session and builds the
  deck from the ids captured at start; there is no path where a session for deck A writes
  decisions while deck B is shown (the VM holds one deck and one cursor).
- `FR-20 resumed session restores the cursor and records no decisions on start` (new): the
  resumed card is the stored one, the store has zero `record` calls on resume, and the delete
  queue is unchanged by resume.
- Skip accounting on resume is covered by existing `6-5 resumed session keeps shuffle order
  and position` (deck position and skip subtraction restored) and
  `FR-15 undoing a skip brings the card back and clears this session's skip`.
- Album vs shuffle sessions cannot cross-contaminate: resume matches on source (and album id);
  decisions are per asset id regardless of which deck showed them, which is the intended
  semantics (a decided asset leaves every deck, FR-9).

## 7. Queue → delete (FR-24–28)

Flow: swipe left → `Decisions` row (`decision='DELETE'`) → `deleteQueue` Flow
(`Decision.sq`: `WHERE decision = 'DELETE'`) → `QueueViewModel` → `CommitDeletionsUseCase`
(the only caller of `MediaLibrary.delete` — enforced by `scripts/check-rules.sh`) →
`PhotoKitMediaLibrary.delete(ids)` → `performChanges { deleteAssets }` →
`markDeleted(success)` only for the intersection of requested ∩ reported-success
(WP-7.6).

- **The ids passed to `delete` are exactly the queued ids:** existing
  `FR-25 commit calls MediaLibrary delete exactly once with every queued id`
  (`CommitDeletionsUseCaseTest` and `QueueViewModelTest`).
- **Restore records KEEP for the right id:** existing
  `FR-24 restore removes item from queue and records KEEP` and
  `FR-24 restore of an id the queue never held records nothing` (`QueueViewModelTest`);
  store-level KEEP-safety of `dropFromQueue` in `SqlStoresTest` (iosTest).
- **The queue shows only ids whose latest decision is a pending DELETE:** enforced by the
  `WHERE decision = 'DELETE'` query plus the one-row-per-asset upsert, and by restore
  rewriting the row to KEEP (tests above); newest-first ordering is `FR-23 deleteQueue is
  newest decision first`.
- **Unresolvable ids are reported failed and stay queued:** existing
  `FR-26 partly resolved batch marks only the resolved ids and reports the rest not found`
  (`CommitDeletionsUseCaseTest`, against the real use case and the resolve-then-delete fake:
  the live batch is deleted, the unresolvable id is not, only the live ids are marked).

### R1 — risk: zero-fetch short-circuit in `PhotoKitMediaLibrary.delete`

`iosMain/platform/PhotoKitMediaLibrary.kt`, `delete()`: when
`fetchAssets(withIdentifiers:)` returns `count == 0` the method returns
`DeleteResult.Deleted(requested)` **without entering `performChanges`**. This behavior is
**deliberate and specified** (REQUIREMENTS §6.8: "A batch whose ids fetch zero assets answers
`Deleted(all requested ids)` without a change request"), and its tests are the existing
`FR-26 batch with no resolvable id converges and marks the whole batch deleted` and
`FR-26 retry after a failed markDeleted converges the unresolved ids` — the short-circuit is
what lets a retry converge after a failed `markDeleted` instead of looping forever.

The residual risk the spec does not address: `PHAssetFetchResult` by identifier has **no error
channel**, so a *transient* fetch failure is indistinguishable from "every asset is already
gone". If a fetch fails transiently, the use case marks live photos deleted, and they are
hidden from every deck forever while remaining in the library — a durable "kept item ends up
queued, then invisible". Partial failure is already safe (WP-7.6): only ids the fetch actually
resolved are deleted and marked.

Severity: low likelihood (needs a transient identifier-fetch failure), high impact (silent
DB/library divergence).

**Suggested fix** (follow-up WP; requires a REQUIREMENTS §6.8 update in the same change):
distinguish "fetched, none exist" from "fetch unavailable" before answering
`Deleted(all requested)` — e.g. only short-circuit when authorization is at least
`.limited`/`.readWrite` and re-fetch each id individually once (`enumerateObjects` on a
non-nil result); otherwise return `Failed(...)` so the rows stay queued and a later retry
converges. `FakeMediaLibrary` already models the unresolvable-id set, so the fake needs no
shape change.

### R2 — note: prune drops verdicts on transiently-missing assets

`PruneQueueUseCase` drops DELETE rows for ids that `items()` cannot resolve. If `items()`
transiently omits live assets, the user's delete verdict is dropped and the photo is
re-presented in the deck. Benign direction (never deletes, never hides), cheap recovery
(re-swipe), but worth knowing. No change suggested now.

## 8. UX findings (not correctness bugs)

- **U1 — diagonal drags commit DELETE, not SKIP.** `previewVerdict`
  (`ui/components/swipe/SwipeMath.kt:80`) tests KEEP, then DELETE, then SKIP, so a drag that
  crosses both the SKIP-up (25 % height) and DELETE-left (35 % width) thresholds shows and
  commits DELETE. A user skipping "up-left" gets a delete.
- **U2 — diagonal flings resolve by the dominant velocity axis.** `flingVerdict`
  (`SwipeMath.kt:59-72`) compares `|vx|` vs `|vy|`; a flick that is mostly *up* (intent SKIP)
  with slightly more horizontal velocity commits DELETE. New `SwipeMathAuditTest` pins both
  behaviors so any future spec change is deliberate.
- **U3 — the FR-22 confirm toast is not modal.** It does not block card drags, and the card
  can be swiped away (or kept) while "This is a favorite — delete anyway?" is on screen; the
  toast itself has no dismiss control (WP-8.9's "no way out"). Safe (the guarded confirm can
  never act on a card other than the one shown), but confusing. Fold gesture-gating and a
  dismiss affordance into the WP-8.9 redesign.
- **U4 — Undo tap during the 220 ms fly-off silently discards that swipe** (see §3). No data
  corruption; card returns. Polish: ignore Undo taps (and card drags) while a dismiss
  animation is in flight, or replay the card.

## Follow-up WPs suggested

1. **WP-A (data safety, small):** harden the R1 zero-fetch short-circuit in
   `PhotoKitMediaLibrary.delete` (distinguish fetch failure from absent before reporting
   `Deleted`). iosMain + `FakeMediaLibrary`, plus a REQUIREMENTS §6.8 update in the same change.
2. **WP-B (small, or fold into WP-8.9):** make card gestures inert while any dialog is up
   (favorite confirm, exit confirm).
3. **WP-C (polish, P2):** U4 fly-off undo affordance.
4. **👤 before changing U1/U2 geometry:** confirm with the friend whether diagonal swipes
   match their intent; any threshold/order change must go through REQUIREMENTS §5.6 together
   with `SwipeSpec.kt` (hard rule 4).

## 👤 Ask the friend (for the main agent)

To pin down what they actually hit: **which deck** was it (All / Album / Favorites / Bursts /
Recently Deleted)? Mostly **swiping or tapping buttons**? Do they use **Undo**, and did they
ever tap it right after a swipe? Did they ever **kill and relaunch** the app mid-session?
Did the favorite dialog ever appear around the time a kept photo looked queued?

## Test inventory (all new, all passing)

| Test | File |
|------|------|
| FR-13 integration out of order decision intents are all dropped and nothing is recorded | `presentation/swipe/SwipeViewModelAuditTest.kt` |
| FR-15 undo before a committed swipe decide arrives leaves no delete row and re-presents the card | ″ |
| FR-15 undo of a KEEP leaves no decision row for the card | ″ |
| FR-15 nothing is undone after a failed write because the decision never reached the stack | ″ |
| FR-15 undo after resume reports nothing to undo and records nothing | ″ |
| FR-20 resumed session restores the cursor and records no decisions on start | ″ |
| FR-22 confirming with the wrong id is ignored and keeps the dialog and the card | ″ |
| FR-22 right swipe while the favorite confirm is up records KEEP not DELETE | ″ |
| FR-13 vertical drags never resolve as a left or right verdict | `ui/components/swipe/SwipeMathAuditTest.kt` |
| FR-13 a diagonal drag past both thresholds previews and commits DELETE not SKIP | ″ |
| FR-13 a diagonal fling is decided by its dominant velocity axis | ″ |

No test reproduces a confirmed wrong-card/wrong-verdict bug — none was found — so no test
is left failing. R1 (the zero-fetch short-circuit) is deliberately **not** pinned by a new
test: its current behavior is already pinned by the existing `FR-26 …converges…` cases, and
follow-up WP-A is expected to change it.
