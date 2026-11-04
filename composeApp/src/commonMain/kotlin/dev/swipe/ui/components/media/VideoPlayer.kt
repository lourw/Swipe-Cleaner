package dev.swipe.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Video and Live Photo playback for one card (FR-16, FR-17; §6.4) — the **only**
 * `expect`/`actual` composable in the app (AGENTS.md rule 5). The `actual` is a
 * `UIKitView` hosting an `AVPlayerLayer` over an `AVPlayer`
 * (`iosMain` `ui/components/media/VideoPlayerView.kt`), fed from the shared
 * [dev.swipe.data.VideoPreloader] implementation resolved through Koin.
 *
 * **Parameters.** [muted] and [playing] drive the player directly (FR-16: videos
 * autoplay muted; the session's `ToggleMute` and `SettingsStore.autoplayVideos` are
 * caller-side — with autoplay off the caller shows the poster plus a play affordance
 * and only passes `playing = true` once tapped, so this component stays dumb).
 * [onProgress] fires roughly every 100 ms with the playhead fraction
 * ([videoProgressFraction]) within the item — it wraps back to 0 on every loop
 * iteration — for the scrubber (FR-16); it also fires for Live Photos, whose
 * caller simply shows no scrubber since the paired video plays once, no loop
 * (FR-17).
 *
 * **At rest only (R2).** The M1 spike (branch `spike-video-interop`) proved the
 * hosted player clips correctly but **ignores `graphicsLayer` rotation and
 * translation** — it stays axis-aligned at its layout rect while the card turns.
 * So the caller must mount this composable only while the card is at rest (not
 * dragging, no flight/return animation in flight) and keep the poster image
 * ([rememberMediaImage]) up for every animated moment; the deck wiring does
 * exactly that (WP-4.2, `DeckMotion.CardMedia`).
 *
 * **Seeking (FR-16 scrubber).** [seekTarget] carries a scrub request (a
 * monotonically increasing [SeekTarget.token] plus the target fraction); each
 * new token seeks the player to `fraction × duration` once, and `null` (the
 * default) never seeks. Repeated tokens keep re-firing even when the fraction
 * repeats, so dragging back to the same spot still seeks.
 */
@Composable
expect fun VideoPlayer(
    id: String,
    muted: Boolean,
    playing: Boolean,
    onProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
    livePhoto: Boolean = false,
    seekTarget: SeekTarget? = null,
)

/**
 * One seek request for [VideoPlayer] (FR-16 scrubber, WP-4.2): [fraction] is
 * the position within the item (`0f..1f`, clamped by the player), and [token]
 * makes every drag step a distinct event so a recomposition with the same
 * fraction still seeks again.
 */
data class SeekTarget(
    val token: Long,
    val fraction: Float,
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

/**
 * The item position (seconds) a scrubber [fraction] maps to — the inverse of
 * [videoProgressFraction] for the FR-16 seek (WP-4.2). [fraction] clamps to
 * `0f..1f`; a non-finite or non-positive duration (unknown, NaN, empty) yields
 * `0.0`, so a seek before the item reports a duration lands at the start
 * instead of dividing into NaN.
 */
fun seekPositionSeconds(
    fraction: Float,
    durationSeconds: Double,
): Double =
    if (!durationSeconds.isFinite() || durationSeconds <= 0.0) {
        0.0
    } else {
        fraction.coerceIn(0f, 1f).toDouble() * durationSeconds
    }
