package dev.swipe.ui.components.swipe

import androidx.compose.animation.core.Spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Threshold and follow-along math from §5.6, checked against the named
 * constants in SwipeSpec.kt (never literals), so editing the spec file is
 * picked up here.
 */
class SwipeMathTest {
    private val cardSize = Size(400f, 800f)
    private val density = 2f

    @Test
    fun `5-6 rotation is clamped to 12 degrees`() {
        val atThreshold = rotationFor(Offset(cardSize.width * COMMIT_FRACTION_HORIZONTAL, 0f), cardSize.width)
        assertEquals(MAX_ROTATION_DEG, atThreshold, 1e-4f)

        // Proportional halfway to the threshold.
        val half = rotationFor(Offset(cardSize.width * COMMIT_FRACTION_HORIZONTAL / 2f, 0f), cardSize.width)
        assertEquals(MAX_ROTATION_DEG / 2f, half, 1e-4f)

        // Dragging past the threshold never exceeds the cap.
        val beyond = rotationFor(Offset(cardSize.width, 0f), cardSize.width)
        assertEquals(MAX_ROTATION_DEG, beyond, 1e-4f)

        // Sign follows the direction; left drags rotate the other way.
        val left = rotationFor(Offset(-cardSize.width * COMMIT_FRACTION_HORIZONTAL, 0f), cardSize.width)
        assertEquals(-MAX_ROTATION_DEG, left, 1e-4f)

        // A purely vertical drag does not rotate.
        assertEquals(0f, rotationFor(Offset(0f, -200f), cardSize.width), 1e-4f)
    }

    @Test
    fun `5-6 horizontal commit at 35 percent width`() {
        val edge = cardSize.width * COMMIT_FRACTION_HORIZONTAL
        assertEquals(
            SwipeVerdict.KEEP,
            verdictFor(Offset(edge + 1f, 0f), Offset.Zero, cardSize, density),
        )
        assertEquals(
            SwipeVerdict.DELETE,
            verdictFor(Offset(-(edge + 1f), 0f), Offset.Zero, cardSize, density),
        )
        // Just below the threshold with no fling: snap back.
        assertNull(verdictFor(Offset(edge - 1f, 0f), Offset.Zero, cardSize, density))
    }

    @Test
    fun `5-6 up commit at 25 percent height`() {
        val edge = cardSize.height * COMMIT_FRACTION_UP
        assertEquals(
            SwipeVerdict.SKIP,
            verdictFor(Offset(0f, -(edge + 1f)), Offset.Zero, cardSize, density),
        )
        assertNull(verdictFor(Offset(0f, -(edge - 1f)), Offset.Zero, cardSize, density))
        // Dragging down never commits SKIP.
        assertNull(verdictFor(Offset(0f, cardSize.height), Offset.Zero, cardSize, density))
    }

    @Test
    fun `5-6 fling over 1000 dp per s commits below threshold`() {
        val flingThreshold = FLING_VELOCITY_DP_S * density
        val smallOffset = Offset(cardSize.width * 0.1f, 0f)
        assertNull(verdictFor(smallOffset, Offset.Zero, cardSize, density))
        assertEquals(SwipeVerdict.KEEP, verdictFor(smallOffset, Offset(flingThreshold + 1f, 0f), cardSize, density))
        assertEquals(SwipeVerdict.DELETE, verdictFor(smallOffset, Offset(-(flingThreshold + 1f), 0f), cardSize, density))
        assertEquals(
            SwipeVerdict.SKIP,
            verdictFor(Offset(0f, -cardSize.height * 0.05f), Offset(0f, -(flingThreshold + 1f)), cardSize, density),
        )
        // Below the fling threshold and below the offset threshold: snap back.
        assertNull(verdictFor(smallOffset, Offset(flingThreshold - 1f, 0f), cardSize, density))
    }

    @Test
    fun `5-6 stamp alpha is linear to threshold`() {
        val edge = cardSize.width * COMMIT_FRACTION_HORIZONTAL
        assertEquals(0f, stampAlpha(SwipeVerdict.KEEP, Offset.Zero, cardSize), 1e-4f)
        assertEquals(0.5f, stampAlpha(SwipeVerdict.KEEP, Offset(edge / 2f, 0f), cardSize), 1e-4f)
        assertEquals(1f, stampAlpha(SwipeVerdict.KEEP, Offset(edge, 0f), cardSize), 1e-4f)
        // Clamped past the threshold.
        assertEquals(1f, stampAlpha(SwipeVerdict.KEEP, Offset(cardSize.width, 0f), cardSize), 1e-4f)
        // Opposite direction shows nothing.
        assertEquals(0f, stampAlpha(SwipeVerdict.KEEP, Offset(-edge, 0f), cardSize), 1e-4f)
        assertEquals(0f, stampAlpha(SwipeVerdict.DELETE, Offset(edge, 0f), cardSize), 1e-4f)

        val upEdge = cardSize.height * COMMIT_FRACTION_UP
        assertEquals(1f, stampAlpha(SwipeVerdict.SKIP, Offset(0f, -upEdge), cardSize), 1e-4f)
        assertEquals(0f, stampAlpha(SwipeVerdict.SKIP, Offset(0f, upEdge), cardSize), 1e-4f)
    }

    @Test
    fun `5-6 under card scale tracks drag progress`() {
        val edge = cardSize.width * COMMIT_FRACTION_HORIZONTAL
        // At rest the underneath card sits back at 0.94 / UNDER_ALPHA_FROM.
        assertEquals(UNDER_SCALE_FROM, underCardScale(Offset.Zero, cardSize), 1e-4f)
        assertEquals(UNDER_ALPHA_FROM, underCardAlpha(Offset.Zero, cardSize), 1e-4f)

        // Halfway to the threshold it is halfway through its 0.94 → 1.0
        // growth; a vertical drag of equal threshold progress tracks too.
        val expectedHalfway = (UNDER_SCALE_FROM + 1f) / 2f
        assertEquals(expectedHalfway, underCardScale(Offset(edge / 2f, 0f), cardSize), 1e-4f)
        val upEdge = cardSize.height * COMMIT_FRACTION_UP
        assertEquals(expectedHalfway, underCardScale(Offset(0f, -upEdge / 2f), cardSize), 1e-4f)

        // At and past the threshold it reaches 1.0 and stays there.
        assertEquals(1f, underCardScale(Offset(edge, 0f), cardSize), 1e-4f)
        assertEquals(1f, underCardScale(Offset(edge * 2f, 0f), cardSize), 1e-4f)

        // Alpha mirrors the scale: UNDER_ALPHA_FROM at rest, 1 at threshold.
        assertEquals(1f, underCardAlpha(Offset(edge, 0f), cardSize), 1e-4f)
    }

    @Test
    fun `5-6 spec constants match the requirements text`() {
        // Guards against accidental edits to the spec file itself (§5.6 and
        // FR-30/FR-31 numbers). If this test fails, either the code or
        // REQUIREMENTS.md §5.6 just moved — reconcile both deliberately.
        assertEquals(0.35f, COMMIT_FRACTION_HORIZONTAL)
        assertEquals(0.25f, COMMIT_FRACTION_UP)
        assertEquals(1000f, FLING_VELOCITY_DP_S)
        assertEquals(12f, MAX_ROTATION_DEG)
        assertEquals(220, FLY_OFF_MS)
        assertEquals(260, UNDO_RETURN_MS)
        assertEquals(30.dp, NUDGE_DP)
        assertEquals(80, NUDGE_MS)
        assertEquals(0.7f, SNAP_DAMPING)
        assertEquals(Spring.StiffnessMediumLow, SNAP_STIFFNESS)
        assertEquals(0.94f, UNDER_SCALE_FROM)
        assertEquals(0.6f, UNDER_ALPHA_FROM)
        assertEquals(0.30f, EDGE_GLOW_MAX_ALPHA)
        assertEquals(12f, STAMP_ROTATION_DEG)
        assertEquals(150, REDUCE_MOTION_CROSSFADE_MS)
    }
}
