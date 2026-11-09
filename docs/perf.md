# Performance measurement (WP-6.3)

How to measure the app against NFR-1–4, what the `os_signpost` spans mean,
what was measured on the simulator, and what still needs a physical device
(👤). Targets are from REQUIREMENTS §4:

| ID | Target |
| --- | --- |
| NFR-1 | Next card at least degraded-visible ≤ 100 ms after a swipe; full image ≤ 500 ms (local assets) |
| NFR-2 | Card drag at display refresh rate (120 Hz ProMotion), no decode-induced dropped frames |
| NFR-3 | < 300 MB resident; ≤ 5 decoded full-screen bitmaps at once |
| NFR-4 | Cold start → interactive Home < 1.5 s with a 50k-item library |

## Signpost spans

`IosPerfTrace` (`iosMain/platform/IosPerfTrace.kt`) emits real `os_signpost`
intervals on subsystem `dev.swipe.cleaner.perf`, category `deck`:

| Span name | Begins | Ends | NFR |
| --- | --- | --- | --- |
| `COLD_START_HOME` | top of `MainViewController()` | first composition of Home content (`HomeDestination`'s `LaunchedEffect(Unit)`) | NFR-4 |
| `NEXT_CARD_DEGRADED` | swipe commit accepted (decision persisted, deck advances) | first image pixels of the new top card (degraded counts) | NFR-1 |
| `NEXT_CARD_FULL` | same commit moment | first **non-degraded** image of the new top card | NFR-1 |

Semantics:

- Each swipe's `begin` gets a fresh signpost id, so a swipe that lands before
  the previous card's image arrives still pairs correctly. `end` closes the
  most recent unmatched `begin` of that name; an unmatched `end` is dropped
  (e.g. `NEXT_CARD_FULL` when the session ends before the full image lands).
- `COLD_START_HOME` is a **lower bound** on true cold start: it starts at our
  first app code, after process spawn, dyld, and UIKit initialization. For
  the full picture use Instruments' App Launch template (see below).
- The spans are emitted from the UI layer (`CommitLatencyTrace` in
  `SwipeScreen.kt`), which is the only layer that sees both the commit moment
  and the image emissions; the span logic itself is tested in
  `SwipePerfTraceTest` with `FakePerfTrace`.

## Capture procedure

**Signposts are volatile**: they are not written to the persisted unified log
store. `log show` finds nothing; you must either watch live or record with
Instruments. Also note `log show`/`log stream` hide signpost records unless
you pass `--signpost` — a missing flag here looks exactly like missing spans.

CLI capture (simulator or device via `cfgutil`/Console):

```bash
# start capturing, then cold-launch or swipe in another terminal:
xcrun simctl spawn booted log stream \
  --predicate 'subsystem == "dev.swipe.cleaner.perf"' \
  --info --debug --signpost
```

Healthy output is a `begin`/`end` pair per event:

```
[spid 0x1, process, begin] SwipeCleaner: [dev.swipe.cleaner.perf:deck] COLD_START_HOME
[spid 0x1, process,   end] SwipeCleaner: [dev.swipe.cleaner.perf:deck] COLD_START_HOME
```

Interval duration = `end − begin` timestamps. For a cold-start number, take
3+ terminate/launch cycles and report the median.

Instruments (required for device numbers, 👤):

1. Seed a **10k+ photo library** first (see "Seeding" below; NFR-4 says 50k).
2. Device, then Product → Profile on the *built* app:
   - **App Launch** template → NFR-4: read the total "Visible" launch time;
     our `COLD_START_HOME` span also appears on the timeline.
   - **Time Profiler** → NFR-1: swipe through ~50 cards with the template
     recording; the two `NEXT_CARD_*` spans appear in the os_signpost track —
     check the P95 of each against 100 ms / 500 ms.
   - **Allocations** → NFR-3: watch the Physical Memory / "VM (empty)"
     footprint while swiping ~100 cards with photos, videos, and Live Photos.
     Healthy: footprint plateaus (the LRU `BitmapLruCache` caps at 5 decoded
     bitmaps); a ratchet that never falls = a leak. Cross-check with the
     Allocations "by type" view for `CGBitmapContext`/`CGDataConsumer` growth.
   - **Animation Hitches** (or Core Animation + Hangs) → NFR-2: drag cards
     continuously for a minute; healthy is zero hitches during drag and no
     hitch spike when a full image swaps in behind the next card.
3. Record results (device, OS, library size, date) in the table below.

Seeding a large library: generate or collect 10k+ files and
`xcrun simctl addmedia booted <dir>/*` (batch it — thousands of files at once
can time out; see `docs/fixtures/README.md` for the fixture mix). A real
device needs real photos (or AirDrop a folder); the NFR numbers are not
meaningful on a 13-item library.

## Results

| NFR | Target | Measured | Device | Library | Date |
| --- | --- | --- | --- | --- | --- |
| NFR-1 degraded | ≤ 100 ms | TODO(👤) — see device instructions below | iPhone, TBD | 10k+ | — |
| NFR-1 full | ≤ 500 ms | TODO(👤) | iPhone, TBD | 10k+ | — |
| NFR-2 drag | 120 Hz, no dropped frames | TODO(👤) (ProMotion device required) | iPhone, TBD | 10k+ | — |
| NFR-3 resident | < 300 MB | TODO(👤) | iPhone, TBD | 10k+ | — |
| NFR-3 bitmaps | ≤ 5 decoded | ≤ 5 by construction (`BitmapLruCache` capacity 5 + in-flight guard; unit-tested) | n/a | n/a | 2026-11-05 |
| NFR-4 cold start | < 1.5 s | TODO(👤) | iPhone, TBD | 50k | — |
| NFR-4 cold start | — | **177–232 ms** (median 189 ms; lower bound, see caveat) | simulator, iPhone 17 (iOS 26.5) — not representative | simulator, 13-item library — not representative | 2026-11-05 |
| NFR-3 resident | — | **≈ 224 MB** RSS (debug builds vary run to run; 218–245 observed) | simulator, iPhone 17 (iOS 26.5), **debug build** — not representative | simulator, 13-item library — not representative | 2026-11-05 |

Simulator smoke numbers (2026-11-05, `iPhone 17` simulator, debug build,
13-item fixture library):

- `COLD_START_HOME`: 231.5 ms, 176.8 ms, 188.8 ms over three terminate/launch
  cycles → median ≈ 189 ms. Lower bound: excludes process spawn, dyld, and
  UIKit init; Instruments App Launch will report more.
- Memory: `ps` RSS of the app process at Home, idle ≈ 224 MB (debug dylib
  mode inflates this and runs vary ±10%; release/device will differ in both
  directions). The 20-swipe watermark could not be measured headlessly (no
  touch automation available); 👤: swipe ~20 cards then re-run
  `xcrun simctl spawn booted /bin/ps axo pid,rss,comm | grep SwipeCleaner`
  (bare `ps` does not resolve under `simctl spawn`), or read Allocations
  on device.
- `NEXT_CARD_*` spans: wiring verified (unit tests + identical emit path to
  the verified `COLD_START_HOME` span); no numbers because swipes can't be
  automated headlessly. 👤: swipe 20–50 cards while streaming as above, or
  record Time Profiler, and fill the NFR-1 row.

### 👤 Device instructions (exact)

1. Build for device (set `TEAM_ID`/`BUNDLE_ID` in
   `iosApp/Configuration/Local.xcconfig`), seed 10k+ items, and run the four
   Instruments templates from "Capture procedure".
2. Fill each TODO(👤) row: measured value (P95 for NFR-1 spans, peak
   footprint for NFR-3, total launch for NFR-4), device model/OS, library
   size, date.
3. Any miss → file a follow-up WP (docs/archive/plan-v1.md §WP-6.3 task 4: prefetch window,
   decode size, etc.) and link it from the row.

## What to watch (hot paths)

Found while tracing; none are NFR misses on their own, all are the first
places to look when Instruments shows a problem:

- **Home count ladder** (`HomeViewModel` + `PhotoKitSourceFetches.kt`):
  every `changes()` tick re-runs the whole estimation→refinement ladder —
  `smartCounts`, `duplicateCandidates`, burst detection — each of
  which enumerates the full asset collection through PhotoKit. On a 50k
  library that is several full passes per library change; Bursts and
  Duplicates each pay two (fetch + refine). If NFR-4 or post-delete Home
  hitches show up, start here: the fix is debouncing `changes()` and caching
  per-bucket counts, not making the enumeration faster.
- **Per-settle AVPlayer churn** (`DeckMotion.kt`): the hosted player mounts
  only while the top card settles (R2) and unmounts on drag/leave. Fast
  swipers pay create/destroy per card. If NFR-2 shows hitches on video-heavy
  stretches, try keeping the player alive for the top-2 cards.
- **`VideoItemCache`** (`iosMain/platform/VideoItemCache.kt`): bounded by
  caller discipline (prefetch window + mount counts), no internal eviction —
  its KDoc defers a real LRU bound to this pass. The deck does `cancel`
  departed ids, so steady state should be ~5 entries; if device Allocations
  shows player items accumulating, add the LRU bound there.
- **`tileMetadata` re-fetch** (`QueueViewModel.kt:132`): re-resolves metadata
  for the whole visible queue whenever anything in it changes. Fine at queue
  sizes ≤ a few hundred; watch if queue-load spikes appear in Time Profiler.
- **`sessionCounts`** (`Decision.sq:57`): counts only `KEEP` and `DELETE`
  rows, so rows already flipped to `DELETED` drop out of the session tally —
  a resumed session's reviewed count under-reports after a delete commit.
  Correctness-adjacent, invisible to the NFRs; candidate for a follow-up.
- **`signingExpiry`** (FR-31): one-shot bundle read at Settings open; not on
  any hot path.

## Implementation notes

- `os_signpost_interval_begin/_end` are macros in the current SDK and expand
  to the exported `_os_signpost_emit_with_name_impl`, so the Kotlin platform
  klib never imports them; `IosPerfTrace` calls the exported function with
  the exact argument shape clang produces (verified against macro-emitted
  records byte-for-byte). Two traps were found empirically and are guarded:
  a NULL caller-dso asserts in libsystem_trace, and a format buffer `size`
  argument ≤ 1 asserts too (use two zero bytes). A NULL `format` segfaults.
- The NSLog fallback discussed in the WP brief was **not needed** — real
  signposts work; no deviation.
- **Release reachability:** the Koin binding and the `MainViewController`
  cold-start begin are unconditional, so signposts ship in release builds.
  Risk is low — `_os_signpost_emit_with_name_impl` is the same hard-linked
  symbol the public macros expand to, so any future OS that removed it would
  break every signposting app, not just this one — and per-event cost is
  nanoseconds. If a release reviewer wants them gone, gate on a build flag.
- Tests: `SwipePerfTraceTest` (commonTest) asserts the commit path opens
  `NEXT_CARD_DEGRADED` + `NEXT_CARD_FULL` and closes them in image order
  using `FakePerfTrace` — including the full-first ordering and that the
  committed card's own late emissions never close the spans.
