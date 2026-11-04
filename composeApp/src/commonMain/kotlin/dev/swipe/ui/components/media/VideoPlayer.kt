package dev.swipe.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Video and Live Photo playback for one card (FR-16, FR-17; §6.4) — the **only**
 * `expect`/`actual` composable in the app (AGENTS.md rule 5). The `actual` is a
 * `UIKitView` hosting an `AVPlayerLayer` over an `AVQueuePlayer`
 * (`iosMain` `ui/components/media/VideoPlayerView.kt`), fed from the shared
 * [dev.swipe.data.VideoPreloader] implementation resolved through Koin.
 *
 * **Parameters.** [muted] and [playing] drive the player directly (FR-16: videos
 * autoplay muted; the session's `ToggleMute` and `SettingsStore.autoplayVideos` are
 * caller-side — with autoplay off the caller shows the poster plus a play affordance
 * and only passes `playing = true` once tapped, so this component stays dumb).
 * [onProgress] fires roughly every 100 ms with the playhead fraction
 * ([videoProgressFraction]) for the scrubber (FR-16); it is not called for Live
 * Photos, which play once with no scrubber. With [livePhoto] the paired video of
 * the asset is exported to a temp file and played once, no loop (FR-17).
 *
 * **At rest only (R2).** The M1 spike (branch `spike-video-interop`) proved the
 * hosted player clips correctly but **ignores `graphicsLayer` rotation and
 * translation** — it stays axis-aligned at its layout rect while the card turns.
 * So the caller must mount this composable only while the card is at rest (not
 * dragging, no flight/return animation in flight) and keep the poster image
 * ([rememberMediaImage]) up for every animated moment; the fallback is wired in
 * WP-4.2.
 */
@Composable
expect fun VideoPlayer(
    id: String,
    muted: Boolean,
    playing: Boolean,
    onProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
    livePhoto: Boolean = false,
)

/**
 * The playhead fraction for [VideoPlayer]'s `onProgress` (FR-16 scrubber):
 * `positionSeconds / durationSeconds` clamped to `0f..1f`. Zero/negative,
 * infinite, or NaN durations (CMTime reports unknown durations as NaN) yield `0f`,
 * and a position past the end clamps to `1f` — the scrubber never divides by zero
 * and never leaves its track.
 */
fun videoProgressFraction(
    positionSeconds: Double,
    durationSeconds: Double,
): Float =
    if (!positionSeconds.isFinite() || !durationSeconds.isFinite() || durationSeconds <= 0.0) {
        0f
    } else {
        (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
    }
