# AGENTS.md

Instructions for coding agents working in this repo. The product spec is in [`REQUIREMENTS.md`](REQUIREMENTS.md). Read it before making changes. It is the source of truth for behavior, UI tokens, and architecture.

## Project at a glance

- Swipe Cleaner: a personal iPhone app for keeping or deleting photos and videos with swipe gestures.
- Kotlin Multiplatform + **Compose Multiplatform** (shared UI). iOS only for now; `androidMain` is a stub.
- The Gradle app module is `composeApp/`. The Xcode wrapper project is `iosApp/`. The package root is `dev.swipe`.

## Commands

All of these were run and verified on 2026-11-05 (Xcode 26.6, iOS 26.5 simulator, JDK 21 from `mise.toml`).

**Setup (once per machine)**

```bash
mise install                                           # JDK 21 + Gradle from mise.toml
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer   # Kotlin/Native + simulators need full Xcode
xcodebuild -downloadPlatform iOS                       # only if `xcrun simctl list runtimes` is empty
scripts/install-hooks.sh                               # pre-push hook runs scripts/validate.sh
cp iosApp/Configuration/Local.xcconfig.example iosApp/Configuration/Local.xcconfig  # then set TEAM_ID / BUNDLE_ID for device builds
```

**Validate (definition of done)**

```bash
scripts/validate.sh            # ktlint → detekt → check-rules → compile → migrations → simulator tests
scripts/validate.sh --full     # also builds the Xcode app; use before merging
```

**Lint**

```bash
./gradlew ktlintFormat                                 # auto-fix formatting
./gradlew ktlintCheck                                  # formatting check
./gradlew detekt                                       # static analysis + Compose rules; report in composeApp/build/reports/detekt/
scripts/check-rules.sh                                 # grep guards for the hard rules below
```

**Build and test**

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64   # fast compile check
./gradlew :composeApp:iosSimulatorArm64Test            # commonTest + iosTest on the simulator
./gradlew :composeApp:allTests                         # all test targets
./gradlew :composeApp:verifySqlDelightMigration        # check .sqm migrations against schema snapshots
./gradlew :composeApp:generateCommonMainSwipeDatabaseInterface  # regenerate SQLDelight after .sq edits
```

**Run the app on a simulator**

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build/xcode build
xcrun simctl boot "iPhone 17"                          # any device from `xcrun simctl list devices available`
xcrun simctl install booted build/xcode/Build/Products/Debug-iphonesimulator/SwipeCleaner.app
xcrun simctl launch booted dev.swipe.cleaner
xcrun simctl addmedia booted <files...>                # seed the photo library (see docs/fixtures/README.md)
```

Or open `iosApp/iosApp.xcodeproj` in Xcode and press Run. On a device, set `TEAM_ID` and `BUNDLE_ID` in `Local.xcconfig` first.

Run `scripts/validate.sh` (or `factory gate` inside a factory worktree) before declaring a code change done, plus any extra checks for the type of change listed in REQUIREMENTS §8.4. **Skip validation for docs-only changes**, meaning the diff touches only markdown or other docs files (`*.md`, `docs/`). If a diff mixes docs and code, validate it. Don't add lint baselines or bare `@Suppress`. If a command above stops working, fix it here in the same change.

## Hard rules

1. **Deletion safety.** The split is the guarantee: `delete` lives on `MediaDeleter`, not on `MediaLibrary`, so readers — every ViewModel — cannot express a delete at all (WP-9.1). `CommitDeletionsUseCase` is the type's only consumer, and `MediaDeleter` is bound in Koin only into it. Never bind or inject `MediaDeleter` anywhere else, never delete on swipe, and never skip the in-app confirmation (NFR-5, FR-25). `scripts/check-rules.sh` keeps `MediaDeleter` out of every other production file; add it to that guard's allowlist only together with a spec change.
2. **Persist before advance.** A swipe decision is written to `DecisionStore` before the UI moves to the next card. If the write fails, the card goes back (NFR-7).
3. **Design tokens only.** Screens and components use `SwipeTheme` tokens (colors, type, spacing, radii from REQUIREMENTS §5). No hex literals, raw `dp` / `sp` numbers, or new colors outside `ui/theme/`. Verdict colors (`keep`, `delete`, `skip`) are used only for those verdicts.
4. **Gesture numbers are spec.** The thresholds, rotation, durations, and springs in REQUIREMENTS §5.6 live as named constants in `ui/components/swipe/SwipeSpec.kt`. Change them only together with the spec.
5. **Layering.** `ui` → `presentation` → `domain` ← `data`. `commonMain` never imports `platform.*`. Platform APIs go behind interfaces in `data/`, with implementations in `iosMain/platform/`, bound through Koin in `di/IosModule.kt`. Prefer interfaces over `expect/actual`. The `VideoPlayer` composable is the one exception.
6. **Never load the whole library.** Fetch asset IDs, then page metadata (20 at a time). Keep at most 5 decoded full-screen bitmaps in memory (NFR-3, NFR-4).
7. **No network or analytics code**, apart from PhotoKit's own iCloud downloads (NFR-6).
8. **Signing stays local.** Don't commit an Apple `DEVELOPMENT_TEAM` or personal bundle ID. They belong in `iosApp/Configuration/Local.xcconfig`, which is gitignored.

## Workflow: factory

Work is planned and built through [`factory`](#factory-commands), a local work queue with an autonomous implementer. There is no `plan.md`, no task file, and no hand-kept tracker: the queue in `.factory/` (local, not in git) is the only record of what is planned, running, parked, and merged. The finished plans are history in `docs/archive/`.

**Who does what**

- **Claude plans.** Every feature and bug is written in Claude Code with the `factory-feature` skill in **full-plan mode**: a spec with testable acceptance criteria, sized tasks, and a complete packet for every task. Don't use quick mode. It leaves the packets to the opencode overseer, and planning is Claude's job here. Refreshing stale packets (`factory stale`) and retros are planning too.
- **opencode builds.** `factory run` builds ready features with the opencode backend (`factory internal config backend` is `opencode`; don't change it). Builder, verifier, and overseer each get one task per session, in a worktree that factory creates and removes.
- **The user lands.** Finished features are squash-merged into the `factory` branch. `factory review` shows what is waiting, and `factory land` fast-forwards `main` to it. Agents run `factory land` only when the user asks.

**Planning rules**

- The builder reads only its task packet, the lessons, and the files the packet lists. Put everything it needs in the packet: cite REQUIREMENTS sections by number, give file refs with line ranges, name the test to add (its name starts with the requirement ID) and the file it goes in.
- Hard rules above bind every task. If a task comes near one (the delete path, gesture constants, tokens, layering), say so in the packet's Constraints.
- Size tasks for opencode: at most 150 changed lines each, one coherent change, and the gate stays green after every task.
- If a feature changes behavior the spec describes, make the `REQUIREMENTS.md` edit part of a task, not an afterthought.
- Two features that edit the same SQLDelight schema or migration, the same Koin module, or the navigation graph will collide when they merge. Chain them with priorities or plan them as one feature.
- Anything that needs a physical iPhone is a 👤 checkpoint. The spec lists it under "Out of scope" with the exact steps, and a task adds those steps to `docs/manual-test.md`.

### Factory commands

```bash
factory board                  # queue health and every feature's next step
factory show <id>              # one feature: tasks, worktree, events, park reason
factory cat plan <id>          # spec, AC coverage, and every packet, as the builder sees them
factory stale                  # packets whose referenced code changed since they were written
factory run                    # build ready features until the queue is empty
factory review                 # what is on the factory branch and not yet on main
factory land                   # fast-forward main to factory (user's call)
factory gate                   # run the gate in the current worktree
factory test '*HomeViewModelTest*'   # one test class (a Gradle --tests filter)
```

The gate is the definition of done, split into checks: `format`, `lint`, `rules`, `typecheck`, `migrations`, `test`, `build`. They are the steps of `scripts/validate.sh --full`. If you change a step in one place, change it in the other (`factory gate config <name> '<command>'`). On a new machine, run `factory init` and set the gate up again with the skill's init mode, because `.factory/` isn't in git.

A parked feature keeps its worktree. Read `factory show <id>`, fix the cause (usually a packet refresh), then `factory set <id> --status ready`.

## Git workflow

- **`main` only moves through `factory land`**, apart from the small changes below. Feature and fix work is never committed to `main` by hand.
- **Factory owns its branches and worktrees.** Don't create, rebase, or remove them by hand; use `factory clean` for leftovers. `git worktree list` should show `main`, factory's worktrees, and nothing else.
- **Commits are conventional**, built from the feature's kind, scope, and title. Keep requirement IDs in the title, for example `fix(swipe): FR-15 undo restores the last card`.
- **Small changes** (a typo, a one-line fix, a docs tweak) don't need a feature. Make them on a short branch in a worktree, validate if they touch code, and fast-forward `main`:

  ```bash
  git worktree add ../swipe-app-wt/<branch> -b <branch> main
  # edit, commit, scripts/validate.sh --full for code changes
  git merge --ff-only <branch> && git worktree remove ../swipe-app-wt/<branch> && git branch -d <branch>
  ```

  If `main` moves this way while the `factory` branch has unlanded work, `factory land` rebases and re-runs the gate first.

## Releases

Xcode Cloud builds from the `release` branch, not `main`. To ship, fast-forward `release` to a validated `main` commit and push it:

```bash
git push origin main:release     # main must already be landed (factory land), pushed, and pass scripts/validate.sh --full
```

Pushing `release` starts a TestFlight build, so only do it when the user asks. Never commit directly to `release`.

## Conventions

- Reference requirement IDs in commit messages and test names, for example `FR-15 undo restores last card`.
- Every use case and ViewModel change comes with a `commonTest`. Use the fakes in `commonTest/.../fakes/` instead of mocking libraries. Use Turbine for Flow assertions.
- ViewModels expose one `StateFlow<XUiState>` and take a sealed `XIntent` through `onIntent()`. Transient drag and animation state stays in composables.
- PhotoKit calls run on `Dispatchers.Default`. Cancel `PHImageRequestID`s when the collecting coroutine is cancelled.
- Schema changes need a SQLDelight migration (`.sqm`). Never edit a migration that has shipped.
- Pin dependency versions in `gradle/libs.versions.toml`. Don't add a library when a small interface plus a platform implementation will do.

## When the spec and the code disagree

Don't silently pick one. If a task requires changing behavior that the spec describes, update `REQUIREMENTS.md` in the same change and call it out in your summary. If the spec is ambiguous, follow the defaults listed under "Open questions" in §2.
