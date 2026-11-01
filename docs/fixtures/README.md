# Simulator fixtures

Media for seeding the simulator photo library (REQUIREMENTS §8.2). The files themselves are
gitignored. Only this README is committed.

Collect at least:

| File | Covers |
| --- | --- |
| A screenshot | Screenshots smart filter (FR-6) |
| A burst (3+ frames) | Bursts smart filter (FR-6) |
| Two photos taken within 2 s at the same dimensions | Likely duplicates (FR-6) |
| A video longer than 60 s | Long videos (FR-6), video playback (FR-16) |
| A short video | Autoplay, mute and scrubber (FR-16) |
| A Live Photo (`.heic` + `.mov` pair) | Long-press playback (FR-17) |
| A handful of ordinary photos across 2+ months | Shuffle, By month (FR-4, FR-7) |

Seed a booted simulator with:

```bash
xcrun simctl addmedia booted docs/fixtures/*
```
