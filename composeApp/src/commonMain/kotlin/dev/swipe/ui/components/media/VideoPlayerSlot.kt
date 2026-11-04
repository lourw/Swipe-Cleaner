package dev.swipe.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The deck's live-playback slot (WP-4.2): [VideoPlayer]'s full signature with
 * the defaults expanded, so screens take the slot as a parameter and their
 * `commonTest` suites pass a recording fake — mounting the iOS `actual` needs
 * the Koin-resolved `VideoItemCache`, which no UI test runs (AGENTS.md rule 5
 * makes `VideoPlayer` itself the only sanctioned platform seam; the slot keeps
 * everything above it fakeable).
 *
 * The default, [DefaultVideoPlayerSlot], is the real [VideoPlayer].
 */
typealias VideoPlayerSlot =
    @Composable (
        id: String,
        muted: Boolean,
        playing: Boolean,
        onProgress: (Float) -> Unit,
        modifier: Modifier,
        livePhoto: Boolean,
        seekTarget: SeekTarget?,
    ) -> Unit

/** The production [VideoPlayerSlot]: the real [VideoPlayer] `expect`. */
val DefaultVideoPlayerSlot: VideoPlayerSlot =
    { id, muted, playing, onProgress, modifier, livePhoto, seekTarget ->
        VideoPlayer(
            id = id,
            muted = muted,
            playing = playing,
            onProgress = onProgress,
            modifier = modifier,
            livePhoto = livePhoto,
            seekTarget = seekTarget,
        )
    }
