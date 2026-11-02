package dev.swipe.ui.components.swipe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.min

// Pure geometry for the swipe gesture (§5.6) — no Compose state, so the
// thresholds, rotation, and under-card tracking are unit-testable in plain
// commonTest (SwipeMathTest). Everything reads its numbers from SwipeSpec.kt,
// the only place §5.6 values are written down.

/**
 * Card rotation in degrees for [offset]: 0 at the center, growing linearly
 * with horizontal progress toward the commit threshold, capped at
 * [MAX_ROTATION_DEG] (§5.6 "Rotation: up to 12° at threshold, proportional
 * to drag"), signed positive right / negative left.
 */
fun rotationFor(
    offset: Offset,
    cardWidthPx: Float,
): Float {
    if (cardWidthPx <= 0f) return 0f
    val progress = min(abs(offset.x) / cardWidthPx / COMMIT_FRACTION_HORIZONTAL, 1f)
    val sign = if (offset.x < 0f) -1f else 1f
    return sign * progress * MAX_ROTATION_DEG
}

/**
 * Opacity of the verdict stamp: 0 at the center, 1 once the drag reaches the
 * commit threshold for its direction (§5.6 "Stamp opacity: linear 0 → 1 at
 * threshold"). A drag in another direction keeps every stamp at 0.
 */
fun stampAlpha(
    verdict: SwipeVerdict,
    offset: Offset,
    cardSize: Size,
): Float {
    if (cardSize.width <= 0f || cardSize.height <= 0f) return 0f
    val progress =
        when (verdict) {
            SwipeVerdict.KEEP -> {
                if (offset.x >= 0f) offset.x / (cardSize.width * COMMIT_FRACTION_HORIZONTAL) else 0f
            }

            SwipeVerdict.DELETE -> {
                if (offset.x <= 0f) -offset.x / (cardSize.width * COMMIT_FRACTION_HORIZONTAL) else 0f
            }

            SwipeVerdict.SKIP -> {
                if (offset.y <= 0f) -offset.y / (cardSize.height * COMMIT_FRACTION_UP) else 0f
            }
        }
    return min(progress, 1f)
}

/** The verdict decided by release velocity alone, or null for no fling. */
private fun flingVerdict(
    velocityPxPerSec: Offset,
    density: Float,
): SwipeVerdict? {
    val flingThreshold = FLING_VELOCITY_DP_S * density
    val horizontal = abs(velocityPxPerSec.x) >= abs(velocityPxPerSec.y)
    return when {
        horizontal && velocityPxPerSec.x > flingThreshold -> SwipeVerdict.KEEP
        horizontal && velocityPxPerSec.x < -flingThreshold -> SwipeVerdict.DELETE
        !horizontal && velocityPxPerSec.y < -flingThreshold -> SwipeVerdict.SKIP
        else -> null
    }
}

/**
 * The verdict a release at [offset] alone would commit (no fling), or null
 * below all thresholds. Also what the stamps and edge glow preview while
 * dragging.
 */
fun previewVerdict(
    offset: Offset,
    cardSize: Size,
): SwipeVerdict? =
    when {
        offset.x > cardSize.width * COMMIT_FRACTION_HORIZONTAL -> SwipeVerdict.KEEP
        offset.x < -(cardSize.width * COMMIT_FRACTION_HORIZONTAL) -> SwipeVerdict.DELETE
        offset.y < -(cardSize.height * COMMIT_FRACTION_UP) -> SwipeVerdict.SKIP
        else -> null
    }

/**
 * The verdict a release commits, or null to snap back: a fling over
 * [FLING_VELOCITY_DP_S] decides by its dominant velocity axis, otherwise an
 * offset past the direction threshold decides (§5.6 "Commit thresholds:
 * 35 % width, 25 % height up — or a fling over 1000 dp/s in that
 * direction").
 */
fun verdictFor(
    offset: Offset,
    velocityPxPerSec: Offset,
    cardSize: Size,
    density: Float,
): SwipeVerdict? {
    if (cardSize.width <= 0f || cardSize.height <= 0f || density <= 0f) return null
    return flingVerdict(velocityPxPerSec, density) ?: previewVerdict(offset, cardSize)
}

/**
 * Drag progress toward the commit threshold in any of the three verdict
 * directions, 0 → 1, clamped: how far the top card has travelled relative to
 * the threshold its gesture would commit against.
 */
private fun dragProgress(
    offset: Offset,
    cardSize: Size,
): Float {
    if (cardSize.width <= 0f || cardSize.height <= 0f) return 0f
    val horizontal = abs(offset.x) / cardSize.width / COMMIT_FRACTION_HORIZONTAL
    val vertical = abs(offset.y) / cardSize.height / COMMIT_FRACTION_UP
    return min(maxOf(horizontal, vertical), 1f)
}

/**
 * Scale of the card underneath while the top card is dragged: 0.94 at rest,
 * growing linearly to 1.0 as the top card's drag progress reaches the commit
 * threshold (§5.6 "Card underneath — Scale 0.94 → 1.0 … tied to the top
 * card's drag progress").
 */
fun underCardScale(
    offset: Offset,
    cardSize: Size,
): Float = UNDER_SCALE_FROM + dragProgress(offset, cardSize) * (1f - UNDER_SCALE_FROM)

/** Opacity of the card underneath: [UNDER_ALPHA_FROM] → 1.0 over the same progress. */
fun underCardAlpha(
    offset: Offset,
    cardSize: Size,
): Float = UNDER_ALPHA_FROM + dragProgress(offset, cardSize) * (1f - UNDER_ALPHA_FROM)
