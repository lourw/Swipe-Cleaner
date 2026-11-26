# WP-9.6 — Loading, empty, and error states for Albums and Months, and no double navigation

_Status: done on branch `fr-8-list-states` (base `b1f0080`). Requirements: FR-7, FR-8, NFR-4, §5.8._

## What was built

- **`ListStatus` + `ListStateMessage`** (`presentation/ListStatus.kt`,
  `ui/screens/home/ListStateView.kt`): the four scan states — loading, ready,
  empty, error — shared by both list screens. The status block is a centered
  `callout textSecondary` message plus, for error, a **Try again**
  `PrimaryButton`; plain text, so VoiceOver reads it as soon as it replaces
  the list (NFR-8).
- **`AlbumsUiState` / `MonthsUiState`** gained `status` (opening in `Loading`).
  The scan's first answer moves the screen to `Ready` or `Empty`; a scan that
  fails while the screen still has nothing to show moves it to `Error`, whose
  Retry re-runs the scan. **Error never replaces shown rows**: a failed later
  refresh keeps what is on screen for the next `changes()` retry (§6.8).
- **Scan failures surface** (`SourceCountsUseCase.albums/months`): the initial
  row-list read is no longer swallowed into an empty Flow — it throws so the
  ViewModel can explain it. Only the per-row count reads stay silent, keeping
  their placeholders (§6.5 *Counts*).
- **No double navigation** (`HomeDestinations.kt`): the Swipe-route collectors
  navigate only while their entry is at `Lifecycle.State.RESUMED`, so two
  events from a double tap land as one deck entry, and events from a
  backgrounded screen never stack onto the deck.
- **`scripts/check-rules.sh`**: the full-screen safe-area guard exempts
  `ListStateView.kt` — it is a block hosted *inside* the inset screens (like
  `ScreenHeader`), not a full-screen surface. The screens keep
  `statusBarsPadding()` on their Column and `navigationBarsPadding()` on their
  `LazyColumn`.

## Tests added

ViewModel (`AlbumsViewModelTest`, `MonthsViewModelTest`):

- `FR-8 the screen opens in the loading state until the scan answers`
- `FR-8 an empty scan answers with the empty state`
- `FR-8 a scan that fails with nothing on screen explains itself and Retry recovers`
- `FR-8 a library change retries the failed scan` (a failed scan also self-heals
  on the next `changes()` emission; Retry is not the only recovery)
- the four `FR-7` mirrors for Months.

Screen (`AlbumsScreenTest`, `MonthsScreenTest`):

- `FR-8 the loading status shows the loading message while the scan runs`
- `FR-8 the empty status says there are no albums`
- `FR-8 the error status explains itself and Try again sends Retry`
- the three `FR-7` mirrors for Months ("Nothing to show").

Navigation (`PermissionFlowContentTest`):

- `FR-8 double tap on an album row pushes exactly one deck route`
- `FR-7 double tap on a month row pushes exactly one deck route`
- `FR-8 events from a backgrounded screen do not stack onto the deck`

## Spec edits

- §5.8 **Albums**: scan-states paragraph (loading / empty / error + Try again;
  error never replaces shown rows).
- §5.8 **By month**: new screen section — rows, exact counts, and the same
  four states with the screen's own copy.
- §6.5 *Counts*: the initial-scan exception — `albums()`/`months()` throws so
  the screen explains itself; `AlbumsUiState`/`MonthsUiState` carry a
  `ListStatus`.

## 👤 Human checkpoints

- Simulator glance at Albums and By month in all four states (seed an empty
  library for Empty; the fakes cover the states in tests, the look is a
  §8.4 check) — message placement under the header/explainer, Try again hit
  target, no Home-indicator collision.
- VoiceOver: the state message is announced when it replaces the list, and
  **Try again** is reachable on the error state.

## Deferred / notes

- A failed per-row count still keeps its placeholder silently (§6.8) — only
  scan failures explain themselves, by design.
- The double-tap tests drive the double tap at the intent level (two
  `OpenAlbum`/`OpenMonth` intents with no idle between them) because the UI
  test harness syncs between clicks and by then the row is behind the deck.
- The branch will be rebased onto `main` by the orchestrator at merge.

## Fix-up pass (verification blockers)

- **B2 — bottom safe area.** The state block now fills the height the host
  gives it and centers its message (`Arrangement.Center`), and both screens
  pass `Modifier.weight(1f).navigationBarsPadding()` at the call site — the
  same way the Ready-state `LazyColumn` takes the inset — so the message
  never sits under the Home Indicator and the `bg` stays full-bleed. Pinned
  by two new bounds tests, `FR-8`/`FR-7 the state block fills the remaining
  space and centers its message, via the `list-state` test tag.
- **Stale-event test miscount (the one real defect).**
  `FR-8 events from a backgrounded screen do not stack onto the deck` failed
  with "Expected <0>, actual <3>" — but the guard was never the problem:
  instrumented runs showed no `navigateIfResumed(Albums…)` call at all, and
  all three back-stack entries (start placeholder, Home, Swipe) decoded
  successfully as `Albums(ALL)` because every `Albums` field has a default.
  The helper now counts entries by destination route (`cd105c3`).
- **`SafeAreaBoundsTest`** passes `status = ListStatus.Ready` explicitly —
  both UiStates open in `Loading` since this WP, so the two WP-6.4 row
  bounds tests were asserting against the loading message (`e8c7c2a`).
- **Nit decisions (no code change, premises verified stale):**
  N2 (rename the `FR-8 double tap` test) — the test exercises the Albums
  route and its `FR-7` sibling the Months route; both names are correct.
  N7 (test-only constructor param on `SourceCountsUseCase`) — the branch
  diff adds no constructor params. B3.3 (delete a "Toast above the action
  bar" line) — that line is not in §5.8, on this branch or on `main`.
  B3.4 — §5.7 already documents the shared `ToastHost` pre-branch. The
  "failed Albums scan does not self-heal" claim was also false: the
  ViewModels keep collecting `library.changes()` and the retry tests prove
  it. A §6.6 note was considered and dropped: §6.6 documents
  `SwipeUiState` only; the `ListStatus` state-shape note is the §6.5
  exception already cited under *Spec edits*.
- A debug `println` left in `HomeDestinations.kt` by an earlier session was
  removed; no debug output ships.
