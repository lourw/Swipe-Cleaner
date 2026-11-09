# Swipe Cleaner

A personal iPhone app for cleaning up your photo library one item at a time with swipe gestures.

- **Swipe right → Keep.** The item is remembered and never shown again.
- **Swipe left → Delete.** The item goes into a _To Delete_ queue. Nothing is deleted until you review the queue and confirm the batch.
- **Swipe up → Skip.** No decision; the item can come back in a later session.

Confirmed deletions go to iOS **Recently Deleted**, where they stay recoverable for 30 days.

Built with **Kotlin Multiplatform + Compose Multiplatform** (shared UI). iOS 17+ only for now; `androidMain` is a stub.

## Features

- **Session sources:** shuffle the whole library, pick an album, browse by month, or use a smart filter (screenshots, long videos, bursts, likely duplicates, selfies). Narrow any source to photos or videos.
- **Fast deck:** full-bleed cards with the next one preloaded, undo (up to 50 steps), video autoplay with mute and scrubber, Live Photo playback on long-press, and badges for Live Photos, iCloud items, and favorites.
- **Safe deletion:** left swipes are queued and saved immediately. You review the queue in a grid, restore anything, and delete the batch with one iOS system confirmation.
- **Remembers everything:** every decision is persisted, so killing the app loses nothing and kept items never come back.
- **Private:** no accounts, sync, network, or analytics code. The only network access is PhotoKit downloading iCloud originals.

## Getting started

Requires macOS with full Xcode 26+ (not just the Command Line Tools), an iOS Simulator runtime, and [mise](https://mise.jdx.dev) for JDK 21 and Gradle.

```bash
mise install
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
scripts/install-hooks.sh                      # pre-push hook runs scripts/validate.sh
cp iosApp/Configuration/Local.xcconfig.example iosApp/Configuration/Local.xcconfig
```

Set `TEAM_ID` and `BUNDLE_ID` in `Local.xcconfig` before building for a device. That file is gitignored, so signing stays local.

**Run on a simulator**

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build/xcode build
xcrun simctl boot "iPhone 17"
xcrun simctl install booted build/xcode/Build/Products/Debug-iphonesimulator/SwipeCleaner.app
xcrun simctl launch booted dev.swipe.cleaner
```

Seed the simulator's photo library with the media listed in [`docs/fixtures/README.md`](docs/fixtures/README.md). Or open `iosApp/iosApp.xcodeproj` in Xcode and press Run.

**Install on an iPhone:** use a free Apple ID through Xcode (re-sign every 7 days) or TestFlight with a paid account. Step-by-step instructions are in [REQUIREMENTS §8.1](REQUIREMENTS.md#81-prerequisites-and-install).

## Validate

```bash
scripts/validate.sh          # ktlint → detekt → check-rules → compile → migrations → simulator tests
scripts/validate.sh --full   # also builds the Xcode app (Debug + Release); run before merging
```

Individual Gradle tasks (lint, tests, SQLDelight) are listed in [`AGENTS.md`](AGENTS.md#commands).

## Project layout

| Path | What's there |
| --- | --- |
| `composeApp/src/commonMain` | Shared UI, presentation, domain, and data layers (`dev.swipe`) |
| `composeApp/src/iosMain` | PhotoKit, AVFoundation, and other iOS implementations, bound with Koin |
| `composeApp/src/commonTest`, `iosTest` | Tests, using fakes rather than mocking libraries |
| `iosApp/` | Xcode wrapper project, signing config, Xcode Cloud scripts |
| `scripts/` | `validate.sh`, `check-rules.sh`, git hooks |
| `config/` | detekt configuration |

Main libraries: Compose Multiplatform, Navigation Compose, Koin, SQLDelight, kotlinx.coroutines. Versions are pinned in `gradle/libs.versions.toml`.

## Documentation

- [`REQUIREMENTS.md`](REQUIREMENTS.md): product spec, design tokens, architecture. The source of truth for behavior.
- [`AGENTS.md`](AGENTS.md): commands, hard rules, git and subagent workflow for contributors and coding agents.
- [`plan.md`](plan.md): open work packages. The completed v1 plan is in [`docs/archive/plan-v1.md`](docs/archive/plan-v1.md).
- [`docs/manual-test.md`](docs/manual-test.md): device checklist for gestures, haptics, and iOS dialogs.
- [`docs/coverage.md`](docs/coverage.md): which test covers each requirement.
- [`docs/perf.md`](docs/perf.md): performance targets and how to measure them.
- [`docs/third-party.md`](docs/third-party.md): third-party asset attributions.

## Releases

Xcode Cloud builds TestFlight releases from the `release` branch. To ship, fast-forward `release` to a validated `main` commit:

```bash
git push origin main:release
```
