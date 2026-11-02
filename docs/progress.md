# Implementation progress (working notes)

Living scratchpad for the main agent while implementing plan.md. Updated at each merge.
Not a spec — `plan.md` and `REQUIREMENTS.md` are the source of truth.

## Environment notes

- **No vision**: this model has no image input. Sub-agents must NOT screenshot or read images.
  Visual QA (fonts, icon rendering, wireframe match) = 👤 checkpoints. Use text checks:
  simctl launch success, runComposeUiTest semantics assertions, simctl spawn ls for files.
- Icons: 15 Material Symbols Rounded vectors pre-converted into wp-1-2 worktree composeResources
  (source: marella/material-symbols svg/400/rounded, viewBox 0 -960 960 960 → 24x24 affine
  x/40, (y+960)/40; converter at /private/var/folders/hq/.../opencode/svg2vector.py).
- `xcode-select` points at CommandLineTools. Everything xcode/simctl/gradle needs
  `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` (validate.sh does this itself).
- JDK 21 active (temurin 21.0.12.1). Simulators available: iPhone 17 (2BE890EF...), iPhone 17 Pro, etc. iOS 26.5.
- Baseline `scripts/validate.sh` on main: PASSED (2026-11-01).
- Worktrees live in `/Users/lourw/personal/swipe-app-wt/wp-<id>` (outside repo). Remove each after merge:
  `git worktree remove ../swipe-app-wt/wp-<id>`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Workflow per WP (from AGENTS.md + plan.md §0)

0. Before dispatching: `ps aux | grep opencode` and check worktree mtimes — on 2026-11-01 a stale
   duplicate session was found editing wp-1-2 concurrently (killed with user approval). Never
   dispatch into a worktree another live session may hold.
1. `git worktree add ../swipe-app-wt/wp-N -b <branch> main` (after deps merged).
2. Dispatch implementer subagent (brief: read AGENTS.md + plan §0 + the WP + cited REQUIREMENTS §s; work only in that worktree; DEVELOPER_DIR export; validate; commit per §0; report §0.9; do NOT merge).
3. Main agent reviews diff; verification subagent for high-risk WPs (2.x gestures, 3.x delete path).
4. Merge `--ff-only` into main (rebase first if main moved), remove worktree, tick tracker here + plan.md.

## Wave plan

Subagents run strictly ONE AT A TIME (AGENTS.md). Serial queue order: 1.2 → 1.3 → 1.6 → 1.4 → 1.5 → 1.7 → 1.8 → ...

| Wave | WPs | Status |
| --- | --- | --- |
| A | 1.1 domain+fakes · 1.2 theme+components | 1.1 MERGED (faaccff) · 1.2 MERGED (c3088cb) |
| B | 1.3 SQLDelight · 1.4 PhotoKit read · 1.5 image loader · 1.6 deck use cases | 1.3 in progress; then 1.6 → 1.4 → 1.5 |
| C | 1.7 nav+onboarding (1.1,1.2,1.4) · 1.8 SwipeVM core (1.3,1.6) | pending |
| D | 1.9 M1 deck screen (1.5,1.7,1.8) | pending |
| E | 2.1 SwipeCard gestures (1.2) · 2.2 SwipeVM full (1.8) | pending |
| F | 2.3 Swipe screen assembly (2.1,2.2) | pending |
| G | 3.1 commit-deletions (1.3) · 3.2 PhotoKit delete (1.4) · 4.1 VideoPlayer (1.9) · 5.1 PhotoKit sources (1.4,1.6) | pending |
| H | 3.3 Queue UI (3.1,3.2,2.3) · 4.2 Video UX (4.1,2.3) · 5.2 HomeVM (5.1,1.3) | pending |
| I | 5.3 Home screens (5.2,1.7) · 6.1 Settings (5.3) — 6.1 after 5.3 | pending |
| J | 6.2 Accessibility (6.1) | pending |
| K | 6.3 Perf pass (6.2) · 6.4 Release sweep (6.3) | pending |

## WP status log

(append one line per WP when merged: branch, merge commit, deviations, open 👤 checkpoints)

### WP-1.2 (m1-components-base) — verified APPROVE-WITH-MINOR, all findings fixed, merged c3088cb
- Icons live in `commonMain/composeResources` (generated accessor is `Res`, not `R`).
- Deviations: Toast injectable-clock requirement met via `autoDismissDelay` param + Compose
  virtual time; PrimaryButton/QueueBadge "white" text = `textPrimary` (noted in §5.7).
- Carried into later briefs:
  - WP-6.2: several components set `contentDescription` duplicating readable child text
    (PrimaryButton/Toast/Banner/SegmentedControl) → possible double announcement in VoiceOver.
  - Debug gallery shows a non-spec `BannerTone.Info` variant next to the spec `Warning` default.
  - Gallery entry point: `App(gallery = true)` exists but no caller; whoever wires debug
    navigation (WP-1.3+) may also capture the 👤 gallery screenshots then.

### WP-1.1 (m1-domain-and-fakes) — verified APPROVE-WITH-MINOR, fixing + re-validating
- Findings 1–2 fixed in fakes directly (openSession/deleteQueue fixed-clock tie-breaks).
- Carried into later briefs:
  - WP-1.6: DuplicateGrouper must use the fake's run-chaining reading of FR-6 (each item ≤2 s
    of its predecessor, groups ≥2, adjacent; chaining allowed beyond 2 s total span).
  - WP-1.6/1.8: §6.4 `sources()` can't carry counts; Home rows use `assetIds(src).size`
    (or richer type later). FakeMediaLibrary.sources() returns [All] only — extend when needed.
  - WP-1.7+: FakeMediaLibrary.changes() has no replay — collect before notifying.
  - WP-1.7+: FakeSettingsStore flows are MutableStateFlow — direct writes bypass setCalls.

## Open 👤 human checkpoints

- WP-1.2: visual confirmation that FontFamily.Default renders as SF Pro (glyph-level);
  component gallery visual QA vs §5.7 wireframes.
