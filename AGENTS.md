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

Run `scripts/validate.sh` before declaring a code change done, plus any extra checks for the type of change listed in REQUIREMENTS §8.4. **Skip validation for docs-only changes**, meaning the diff touches only markdown or other docs files (`*.md`, `docs/`). If a diff mixes docs and code, validate it. Don't add lint baselines or bare `@Suppress`. If a command above stops working, fix it here in the same change.

## Hard rules

1. **Deletion safety.** `CommitDeletionsUseCase` is the only code that calls `MediaLibrary.delete`. Never add another call path, never delete on swipe, and never skip the in-app confirmation (NFR-5, FR-25).
2. **Persist before advance.** A swipe decision is written to `DecisionStore` before the UI moves to the next card. If the write fails, the card goes back (NFR-7).
3. **Design tokens only.** Screens and components use `SwipeTheme` tokens (colors, type, spacing, radii from REQUIREMENTS §5). No hex literals, raw `dp` / `sp` numbers, or new colors outside `ui/theme/`. Verdict colors (`keep`, `delete`, `skip`) are used only for those verdicts.
4. **Gesture numbers are spec.** The thresholds, rotation, durations, and springs in REQUIREMENTS §5.6 live as named constants in `ui/components/swipe/SwipeSpec.kt`. Change them only together with the spec.
5. **Layering.** `ui` → `presentation` → `domain` ← `data`. `commonMain` never imports `platform.*`. Platform APIs go behind interfaces in `data/`, with implementations in `iosMain/platform/`, bound through Koin in `di/IosModule.kt`. Prefer interfaces over `expect/actual`. The `VideoPlayer` composable is the one exception.
6. **Never load the whole library.** Fetch asset IDs, then page metadata (20 at a time). Keep at most 5 decoded full-screen bitmaps in memory (NFR-3, NFR-4).
7. **No network or analytics code**, apart from PhotoKit's own iCloud downloads (NFR-6).
8. **Signing stays local.** Don't commit an Apple `DEVELOPMENT_TEAM` or personal bundle ID. They belong in `iosApp/Configuration/Local.xcconfig`, which is gitignored.

## Working with subagents

Use subagents for implementation and verification wherever you can. The main agent plans, sends out the work, merges, and checks each result. It doesn't write the feature code itself.

**Run at most 2 subagents at a time**, on top of the main (orchestrating) agent. Implementers and verifiers both count toward the pool. When a slot frees up, fill it with the next task from the queue.

- **Implementation.** Each work package (WP) in [`plan.md`](plan.md) goes to its own subagent, briefed with the "Sub-agent brief template" in `plan.md` §0, each in its own worktree and branch (see Git workflow).
- **Pick tasks that can run in parallel.** Two WPs can run side by side only if neither depends on the other (`plan.md` §1 waves and each WP's "Depends on") and they won't conflict: they don't edit the same files, the same SQLDelight schema or migration, or the same Koin module or navigation graph. To fill a slot, walk down the queue in order and take the first WP that is unblocked and doesn't conflict with the running one. Skip WPs that depend on or overlap with running work; they stay queued for a later slot. If nothing fits, leave the slot empty.
- **Verification.** When a WP is done, a *separate* subagent reviews it against the REQUIREMENTS sections the WP cites, the hard rules above, and the `plan.md` §0 definition of done. Give it the branch diff and the WP, not the implementer's summary, so it reviews the code rather than confirming the summary. Send its findings back to the implementer, or to a new fix-up subagent, before merging.
- **Main agent.** It owns the branch merges (including resolving conflicts between parallel branches, see Git workflow), the 👤 human checkpoints, and the summary for the user. It doesn't re-run checks a subagent already ran and reported as passing, unless the result looks wrong.
- **Exception: small changes.** A change of a few lines (a typo, a one-line fix, a docs tweak) can be done directly, because writing the brief would cost more than making the change.

## Git workflow

- **Never commit directly to `main`.** Every feature, fix, or doc change gets its own branch **and its own worktree**, created from an up-to-date `main`. Name the branch after the milestone or requirement, such as `m2-swipe-gestures` or `fr-15-undo`. Worktrees live outside the repo in `../swipe-app-wt/<branch>`:

  ```bash
  git worktree add ../swipe-app-wt/<branch> -b <branch> main
  ```

  The main checkout stays on `main`. Work, validation, and commits for the branch happen only inside its worktree.
- **Commit as you go.** Make small commits, each one with a working build, rather than one large commit at the end.
- **Merge into `main` only when the work is done**, meaning `scripts/validate.sh --full` passes in the worktree. Docs-only branches skip validation and can be merged directly. Then, from the main checkout, merge and **always remove the worktree**:

  ```bash
  git merge --ff-only <branch>     # if main has moved on: rebase <branch> onto main in its worktree, re-validate, then merge
  git worktree remove ../swipe-app-wt/<branch>
  git branch -d <branch>
  ```

  Don't leave worktrees around after merging.
- **Merging parallel work.** When both parallel subagents are done, the main agent merges both branches itself; it doesn't hand this back to a subagent or ask the user first. Merge the first branch as above. Then rebase the second onto the new `main` in its worktree and resolve any conflicts so the result contains **both** changes: keep each branch's behavior, combine edits to shared files, and renumber colliding SQLDelight migrations or schema versions. Re-run `scripts/validate.sh --full` on the combined result, then merge it.
  - **Ask the user only if the two changes are incompatible**, meaning you can't keep both without changing what one of them does. Examples: they implement contradicting behavior for the same requirement, or one removes or redesigns something the other relies on. In that case, stop before merging the second branch, say what conflicts, and offer options. Merge conflicts that just need both sides combined are not a reason to ask. `git worktree list` should show only `main` and the branches still in progress.

## Releases

Xcode Cloud builds from the `release` branch, not `main`. To ship, fast-forward `release` to a validated `main` commit and push it:

```bash
git push origin main:release     # main must already be pushed and pass scripts/validate.sh --full
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
