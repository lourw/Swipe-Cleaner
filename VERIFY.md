# WP-9.3 verification — `arch-toast-host` (fa1f065)

**Verdict: PASS-with-nits.** No blockers: no spec violation, no hard-rule violation, no incorrect behavior found. Five nits below; none should block merge (fix #1 and #2 cheaply if a fix-up pass happens anyway).

## Checks run

| Check | Result |
| --- | --- |
| `./gradlew :composeApp:iosSimulatorArm64Test` | **418 tests, 0 failures, 0 errors, 0 skipped** (fresh run on this worktree; report timestamps verified, re-run confirmed UP-TO-DATE) |
| `scripts/check-rules.sh` | pass ("all hard-rule guards pass") |
| `./gradlew detekt ktlintCheck` | pass (up-to-date against current inputs) |
| Diff review | all 6 commits, all 14 files, every hunk |
| Worktree | clean, no stray edits |

## Implementer's claims — verification

| # | Claim | Verdict | Evidence |
| --- | --- | --- | --- |
| 1 | ToastHost owns the ONLY 3 s timer and dismissal signal; confirm never auto-dismisses; Toast.kt stripped (no `dismissed`, no `LaunchedEffect`, no `autoDismissDelay`/`onDismiss`, `NEVER_AUTO_DISMISS` deleted) | **Confirmed** | `Toast.kt` diff is pure presentation; the only `delay(` in `ui/` is `ToastHost.kt:70`; grep for `autoDismiss*`/`NEVER_AUTO_DISMISS` finds only the spec flag (`autoDismisses`) and ToastHost itself. `autoDismisses=false` skips the `LaunchedEffect` entirely (`ToastHost.kt:68-72`). |
| 2 | One placement modifier `Modifier.toastHostPlacement(clearHomeIndicator)`; Swipe passes `false` (above action bar), Queue/Settings/empty-state use the default (bottom + Home-Indicator clearance) | **Confirmed** | `ToastHost.kt:89-99`; `SwipeScreen.kt:326` (`clearHomeIndicator = false`), `SwipeScreen.kt:139`, `QueueScreen.kt:164`, `SettingsScreen.kt:138-141`. Queue moved top→bottom; Settings' hand-rolled `navigationBarsPadding + xl` replaced. |
| 3 | Queue delete errors show plain "Couldn't delete — try again"; raw PhotoKit message never rendered | **Confirmed** | `QueueScreen.kt:93` folds `ShowDeleteError` into `deleteFailedToast` (`QueueScreen.kt:302`); `message` is referenced nowhere in `ui/` (grep). `QueueEvent.kt` KDoc updated. Test `6-7 …` asserts plain copy present **and** "PhotoKit said no" absent. |
| 4 | Scrubber row height reserved on every card; toast is an overlay in the stage Box | **Confirmed** | `SwipeScreen.kt:262-287` (stage Box, `weight(1f)`, overlay aligned BottomCenter); reserved row `SwipeScreen.kt:429-446` uses the **existing token** `SwipeTheme.componentSizes.videoScrubberTouchHeight` (`Dimens.kt:83`, 44 dp) — no raw dp. |
| 5 | Spec updated: §5.7 Toast row + "One toast host (WP-9.3)" paragraph in §6.6 | **Confirmed** | Diff matches code: timer in host, at most one toast per screen, bottom-anchored, deck overlays the card so it never resizes, confirm (`autoDismisses=false`) stays until its owner removes it, plain error copy. One nuance: the paragraph says the toast "overlays the card's bottom edge" — geometrically it overlays the reserved scrubber row at the stage's bottom edge (see nit 3). |
| 6 | check-rules: SwipeScreen allowlisted with recorded reason; ActionBar keeps its guard | **Confirmed** (comment wording loose — nit 4) | `check-rules.sh` comment records the reason; `ActionBar.kt) need='navigationBarsPadding'` untouched; script passes. |
| 7 | Tests: FR-15/FR-16 card-size, queue/settings position + 3 s fuse, 5 ToastHostTest, delete-error tests assert raw message absent, ToastTest rewritten | **Confirmed** | All present in the run: ToastHostTest 5/5, ToastTest 2/2, `FR-15 the card keeps its exact size when a toast appears`, `FR-16 … across photo and video cards`, `WP-9-3 …` position/fuse ×4, queue raw-message-absent assert. |

## Focus checks

1. **Timer ownership** — exactly one. `delay(` appears once in `ui/` (ToastHost). The gallery composes `Toast` directly with a comment (no timer, debug-only, acceptable).
2. **FR-22 confirm** — `autoDismisses = !confirm` (`SwipeScreen.kt:183`); the deck Delete action reads `state.pendingFavoriteDelete` at tap time and sends `ConfirmFavoriteDelete(pending)` (`SwipeScreen.kt:316-320`); `FR-22 favorite confirm toast offers a Delete action` passes and asserts the intent names the pending card (FR-13). The 220 ms fly-off interaction is the pre-existing WP-9.2 item, not a regression.
3. **§5.7 / hard rule 3** — production diff contains no hex literals and no raw `dp`/`sp`; every new number is either an existing token (`videoScrubberTouchHeight`, `spacing.gutter`, `spacing.s`) or in test code. Layering intact: ToastHost is pure `ui`, no new Koin/navigation/domain changes.
4. **Raw PhotoKit text** — nothing else renders it; `toastText()` maps deck toasts to fixed copy.
5. **check-rules honesty** — see nit 4; ActionBar's guard kept.
6. **Spec vs code** — the new §6.6 paragraph describes what the code does (verified point by point, table row 5).
7. **Commands** — run and reported above; full validate.sh not re-run per brief.
8. **Test quality** — the size tests assert exact `boundsInWindow` equality and the position tests compare toast bounds to the screen root: the FR-15/FR-16 size tests and the queue position test **would fail on the old layout** (Column toast shrinks the card; scrubber swap resizes it; queue toast hung from the top). The Settings position/fuse tests would also have passed pre-WP (old Settings toast was already bottom with a 3 s Toast timer) — they guard the behavior rather than discriminate the change; acceptable, but worth knowing.

## Findings

1. **Nit** — `composeApp/src/commonMain/kotlin/dev/swipe/ui/components/ToastHost.kt:44-48`: the KDoc reads "When the 3 s fuse lands (or the user taps the action of a toast whose owner clears it on tap), [onDismiss] tells the owner to drop the spec". The host never calls `onDismiss` on an action tap — only the timer does; the parenthetical describes owner-side behavior and is easy to misread as a second dismissal path (the exact thing this WP eliminates). Reword.
2. **Nit** — coverage gap: no deck-level test that the **FavoriteConfirm** mapping (`SwipeScreen.kt:183`, `autoDismisses = !confirm`) survives the fuse. `ToastHostTest` proves `autoDismisses=false` never fires, and `SwipeScreenTest.kt:224` proves the Undone fuse fires through the screen, but if the mapping were inverted, every test would still pass (the FR-22 test at `SwipeScreenTest.kt:230` never advances the clock). A ~6-line test (FavoriteConfirm + `advanceTimeBy(3100)` + assert no `DismissToast` intent) closes it. Pre-existing gap, not a regression.
3. **Nit (👤 visual-check note)** — the deck toast overlays the reserved scrubber row, and the Queue/Settings toasts now overlay the bottom CTA/footer (e.g. "Couldn't delete — try again" sits over the Delete button for 3 s). This is what WP-9.3 mandated ("one placement (bottom)", "overlay anchored above the action bar"), and the non-clickable toast body does not block taps in Compose, but it should get an eyeball on device alongside the existing WP-9.2 checkpoint.
4. **Nit** — `scripts/check-rules.sh:90-95`: the allowlist comment says SwipeScreen "delegates both bars"; in the empty-state branch (no ActionBar) the toast still clears the Home Indicator itself via `toastHostPlacement()` — which the guard cannot grep (it scans only `ui/screens` + the gallery). Behavior is preserved from the old code; the comment just undersells what SwipeScreen still does.
5. **Nit (pre-existing, inherited)** — re-raising an identical toast within 3 s does not restart the fuse: `mutableStateOf` ignores the equal write and `LaunchedEffect(current)` in `ToastHost.kt:69` keys on spec equality (e.g. two Undos back-to-back, or two identical Settings failures). The old `Toast` had the same `LaunchedEffect(text, …)` behavior, so this is not a regression — noting it so it is a conscious choice.

## Summary

The branch does what WP-9.3 asked: one `ToastHost` with the only timer, one bottom placement, the deck card no longer resizes for toasts or card-type swaps, Queue shows plain retry copy, the spec records the choice, and the test suite (418 green) includes real geometry assertions that pin the fixes. Merge-ready; nits 1 and 2 are worth a quick fix-up if one is running for other reasons, nit 3 rides the existing device checkpoint.
