package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.TABULAR_FIGURES

/** Tag the screen tests drag through for the FR-16 scrubber. */
internal const val VIDEO_SCRUBBER_TEST_TAG = "video-scrubber"

/**
 * The FR-16 video scrubber (§5.7 wireframe: `──●────── 0:12`), mounted by the
 * deck **outside the card, in the action-bar area** — §5.6 keeps every
 * horizontal drag on the card itself, so the two gestures never conflict.
 * Videos only: Live Photos play once with no loop, so the deck shows no
 * scrubber for them (FR-17).
 *
 * The whole [SwipeTheme.componentSizes.videoScrubberTouchHeight] band is the
 * drag target; inside it the §5.7 2 dp progress line doubles as the track,
 * filled to the visible fraction with a thumb, and the elapsed time rides at
 * the end (`caption`, tabular figures, [formatDuration]). Dragging reports the
 * fraction under the finger through [onScrub] (the caller seeks live) and
 * signals the gesture's end — release or cancel — through [onScrubEnd].
 */
@Composable
internal fun VideoScrubber(
    durationMs: Long,
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeTheme.colors
    val sizes = SwipeTheme.componentSizes
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(sizes.videoScrubberTouchHeight)
                .padding(horizontal = SwipeTheme.spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(VIDEO_SCRUBBER_TEST_TAG)
                    .semantics {
                        // VoiceOver reads the position; the drag itself is
                        // pointer-only, matching the card's gesture stack.
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                    }.pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> onScrub(scrubFractionAt(offset.x, size.width.toFloat())) },
                            onHorizontalDrag = { change, _ ->
                                onScrub(scrubFractionAt(change.position.x, size.width.toFloat()))
                            },
                            onDragEnd = onScrubEnd,
                            onDragCancel = onScrubEnd,
                        )
                    },
            contentAlignment = Alignment.CenterStart,
        ) {
            ScrubberTrack(
                fraction = fraction.coerceIn(0f, 1f),
                modifier = Modifier.matchParentSize(),
            )
        }
        BasicText(
            // Elapsed time follows the visible fraction, so the label tracks
            // the finger while scrubbing, not the player's last report.
            text = formatDuration((fraction.coerceIn(0f, 1f) * durationMs).toLong()),
            style =
                SwipeTheme.typography.caption.copy(
                    color = colors.textPrimary,
                    fontFeatureSettings = TABULAR_FIGURES,
                ),
            modifier = Modifier.padding(start = SwipeTheme.spacing.xs),
        )
    }
}

/** The 2 dp track with the filled run and thumb, drawn so the line stays crisp. */
@Composable
private fun ScrubberTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeTheme.colors
    val thickness = SwipeTheme.componentSizes.progressLineThickness
    Canvas(modifier = modifier) {
        val linePx = thickness.toPx()
        val y = (size.height - linePx) / 2f
        drawRect(
            color = colors.border,
            topLeft = Offset(0f, y),
            size = Size(size.width, linePx),
        )
        val x = size.width * fraction
        drawRect(
            color = colors.accent,
            topLeft = Offset(0f, y),
            size = Size(x, linePx),
        )
        // The §5.7 wireframe thumb: a circle on the fill's leading edge,
        // sized from the track token so no raw number enters the draw.
        drawCircle(
            color = colors.accent,
            radius = linePx * 2f,
            center = Offset(x, size.height / 2f),
        )
    }
}

/**
 * The scrub fraction for a drag at [x] across a track [widthPx] wide
 * (FR-16): `x / widthPx` clamped to `0f..1f`; a non-positive or non-finite
 * width yields `0f`, so a pre-layout drag can never divide by zero.
 */
fun scrubFractionAt(
    x: Float,
    widthPx: Float,
): Float =
    if (!x.isFinite() || !widthPx.isFinite() || widthPx <= 0f) {
        0f
    } else {
        (x / widthPx).coerceIn(0f, 1f)
    }
