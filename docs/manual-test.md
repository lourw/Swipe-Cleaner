# Manual test checklist

The device checklist from REQUIREMENTS §8.2. Automated tests can't feel gestures,
haptics, or the iOS dialogs, so these checks run by hand. Work the sections in
order; each check is `☐ pass / fail` — file an issue (or fix it) for every fail.

**Where to run.** The simulator is enough for flow checks, but gestures, motion,
and haptics must be run **on a physical device** (§8.4: the simulator has no
haptics and no 120 Hz). Rows that need a device are marked 👤.

## Setup

```bash
# build + install + launch (see AGENTS.md "Run the app on a simulator"; on a
# device use Xcode ⇧⌘R with Local.xcconfig set)
xcrun simctl boot "iPhone 17"
xcrun simctl install booted build/xcode/Build/Products/Debug-iphonesimulator/SwipeCleaner.app
xcrun simctl launch booted dev.swipe.cleaner

# seed the library if it is empty (fixtures: docs/fixtures/README.md)
xcrun simctl addmedia booted docs/fixtures/*
```

Start from Home with **All photos → Shuffle** (the plain deck) unless a check
says otherwise.

## Swipe

Step-by-step checks for every §5.6 row, plus the screen assembly (§5.7, §5.8).

**Deck chrome (FR-19, FR-10, FR-18)**

1. ☐ The top bar shows `reviewed / total` centered (e.g. `128 / 2,340`, with
   thousands separators), close × at the left, the queue button at the right,
   and a 2 dp progress line under the count that grows as you review.
2. ☐ Queuing an item (swipe or button Delete) puts a red badge with the queue
   count on the queue button; at 0 the badge is hidden (FR-10).
3. ☐ The card shows its capture date at the bottom over a scrim gradient
   (videos also show their duration).

**Drag mechanics (§5.6 rows 1–3)**

4. ☐ *Horizontal commit threshold*: drag right slowly. Nothing commits before
   the card's trailing edge passes ~35 % of the card width; past it, releasing
   commits Keep. Repeat leftward for Delete.
5. ☐ *Fling threshold*: a short, fast horizontal flick (well under 35 % travel)
   still commits — release velocity ≥ 1000 dp/s is enough.
6. ☐ *Up threshold*: drag up slowly; Skip commits past ~25 % of the card
   height, and a fast upward fling commits from less.
7. ☐ 👤 *Rotation*: while dragging sideways the card rotates proportionally
   (12° at the full threshold), pivoting at its **bottom center**, clamped at
   ±12° — it never rotates further even on a big over-drag.

**Stamps and glow (§5.6 rows 4–6)**

8. ☐ *Stamp opacity*: the KEEP/DELETE/SKIP stamp fades in linearly — invisible
   at rest, fully opaque exactly at the threshold.
9. ☐ *Stamp position and style*: KEEP top-left tilted −12°, DELETE top-right
   tilted +12°, SKIP bottom center (shown on an upward drag). Each is a 3 dp
   outline in its verdict color (green / red / gray) with a transparent fill.
10. ☐ *Edge glow*: a gradient in the verdict color appears on the swipe side,
    reaching at most ~30 % alpha at the threshold.

**Commit and return animations (§5.6 rows 7–11)**

11. ☐ 👤 *Fly-off*: on commit the card continues in the release direction off
    screen in ~220 ms, accelerating out (FastOutLinearIn).
12. ☐ *Snap back*: release below every threshold and the card springs back to
    rest (slight overshoot, no bounce loop) and stays on the deck.
13. ☐ *Card underneath*: while dragging, the next card grows from 0.94 to 1.0
    and fades from 0.6 to 1.0, tracking your finger; it snaps back with a
    below-threshold release.
14. ☐ *Undo return*: tap Undo (⟲) — the card flies back in from the side it
    left, in ~260 ms. Undo several times in a row: each previous card returns
    and the reviewed count decreases.
15. ☐ *Button path*: tapping the ActionBar Keep / Delete / Skip first nudges
    the card ~30 dp (80 ms), then runs the same fly-off; the decision commits
    only after the card leaves.

**Haptics (§5.6 row 12 — all 👤, device only)**

16. ☐ A selection tick fires the moment the drag crosses a commit threshold
    (entering a verdict zone, either direction).
17. ☐ Keep and Skip commits give a **light** impact; Delete gives a **medium**
    impact.
18. ☐ 👤 `notificationSuccess` after a batch delete — see the Delete section,
    row 13.

**Reduce Motion (§5.6 row 13 — 👤)**

19. ☐ With iOS Settings → Accessibility → Motion → **Reduce Motion** on,
    swiping shows no rotation and no fly-off: the stamps still appear during
    the drag, and commit is a ~150 ms crossfade to the next card.

**Other card gestures (§5.6)**

20. ☐ *Tap a photo*: the chrome (top bar + metadata) toggles off and on; the
    card does not move. (Video tap is in the Video section.)
21. ☐ *Double-tap*: the photo toggles between aspect-fit (default) and
    aspect-fill; another double-tap goes back.
22. ☐ *Long-press*: no-op for now (Live Photo playback arrives in WP-4.2).

**Decisions, toasts, and the session (FR-12…FR-15, FR-21, FR-22, NFR-7, §5.8)**

23. ☐ Swipe / button decisions persist: kill the app mid-session and relaunch —
    it resumes at the same card with the same counts (FR-20).
24. ☐ Undo shows the "Undone" toast; it dismisses after ~3 s and the Undo
    button is disabled when there is nothing to undo (FR-15).
25. ☐ Deleting a **favorite** keeps it queued only after confirming: the toast
    "This is a favorite — delete anyway?" with a **Delete** action appears
    instead; tapping Delete queues it, letting it sit dismisses the toast and
    the card stays on the deck (FR-22).
26. ☐ (Error path, if reproducible: turn on airplane mode with a decision write
    failing → "Couldn't save — try again" and the card returns to the deck,
    NFR-7.)
27. ☐ Finish the deck: the last card leaves, the empty deck shows the session
    **Summary** — big counts with colored labels `Kept` / `Queued` / `Skipped`
    — and the CTA is "Review N to delete" when N > 0 (opens the review queue,
    see the Delete section) or "Back home" when the queue is empty (FR-21).
28. ☐ Starting a deck with no matching media shows "Nothing left here 🎉" +
    "Try another source", which returns Home (§5.8 empty states).
29. ☐ Close × returns Home **without clearing the session**; starting the same
    source again resumes (FR-20, §6.5).

## Delete

Step-by-step checks for the review queue and committed deletion (FR-23–28,
NFR-5). Seed at least 5 photos and queue all of them (swipe left ×5), then
open the queue from the badged queue button or the summary's
"Review N to delete" CTA.

**Queue review (FR-23, FR-24)**

1. ☐ The queue shows a 3-column square grid of the queued items, oldest
   first, with the count in the header; scrolling a long queue stays smooth
   (thumbnails pop in degraded-then-sharp, no full-screen stalls).
2. ☐ Tapping a tile opens the full-screen item viewer; **Close** returns to
   the grid with the same scroll position.
3. ☐ In the viewer, **Restore** removes the item from the queue (the grid
   re-renders with one fewer tile and the header count drops) and the item
   stays in the library.
4. ☐ VoiceOver (Settings → Accessibility): each tile announces its capture
   date and type ("Photo, March 12, 2025"), the delete button its count
   (NFR-8). 👤 for the full read-out.

**The double confirm (NFR-5, FR-25)**

5. ☐ **Delete N** opens an in-app dialog — "Delete N items?" with the caption
   "They'll move to Recently Deleted for 30 days." Nothing is deleted yet:
   the items are still in the Photos library.
6. ☐ **Cancel** in the in-app dialog closes it; the queue is unchanged.
7. ☐ **Delete** in the in-app dialog raises the **iOS system dialog**
   ("Swipe Cleaner Would Like to Delete Photos and Videos" or similar).
   **Don't Delete** there dismisses it and the queue is unchanged (FR-27).
8. ☐ Confirming the iOS dialog deletes the batch: a success screen shows the
   count, "Deleted", and **Back home**, which lands on Home. The queue is
   empty afterwards; reopening it shows "Nothing left here 🎉" with a
   "Back home" CTA (§5.8).
9. ☐ Check the Photos app: the deleted items appear in **Albums → Recently
   Deleted** (30-day retention) and are gone from the main library.

**Errors and edge cases (FR-28)**

10. ☐ Delete some queued items in the Photos app while the queue is open (or
    from a second run) — they disappear from the queue on return without an
    app restart, and the header count follows.
11. ☐ Error path (if reproducible: airplane mode with an iCloud-only asset in
    the batch): the iOS dialog's delete fails → a toast carrying the system's
    error message and the queue is unchanged (FR-28). A failed Restore shows
    "Couldn't restore — try again".
12. ☐ Queue everything, delete the batch, then start a new deck over the same
    source: deleted items never appear in the deck.
13. ☐ 👤 `notificationSuccess` haptic fires once after a successful batch
    delete (§5.6 row 12).

## Video

> **Stub.** Filled by WP-4.2 (playback, scrubber, Live Photos, badges).

Will cover: autoplay + tap-to-mute (FR-16), the scrubber living in the
action-bar area outside the card (a horizontal drag on the card must never
move it, §5.6), a >60 s video, Live Photo long-press (FR-17), and the cloud /
favorite / Live badges.

## Permissions

1. ☐ First launch shows the onboarding screen before any PhotoKit prompt.
2. ☐ "Get started" triggers the iOS photo library permission dialog. **Allow**
   → the app proceeds to Home.
3. ☐ **Don't allow** → the denied screen appears with a route to iOS Settings;
   the app never shows a broken empty library.
4. ☐ iOS Settings → Photos → **Limited access**: pick 2–3 photos; the app
   decks exactly those, and a "Manage/Edit photos" entry opens the system
   picker. Adding more there updates the deck on return.
5. ☐ Revoke fully in iOS Settings, reopen the app → back on the denied screen.

## Settings

> **Stub.** Filled by WP-6.1 (FR-29–31).

Will cover: haptics toggle silencing every haptic (👤), include-iCloud-only,
autoplay videos, "Reset all decisions" (confirm required; queue survives,
FR-30), and the signing-expiry row (FR-31).
