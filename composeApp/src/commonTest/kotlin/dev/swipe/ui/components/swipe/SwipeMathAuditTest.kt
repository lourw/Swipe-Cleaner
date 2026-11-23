package dev.swipe.ui.components.swipe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WP-7.4 audit pins for the §5.6 verdict geometry (report:
 * docs/audits/2026-11-decision-audit.md, findings U1/U2): what a short or
 * mostly-vertical drag can and cannot resolve to, and which verdict wins when
 * a diagonal drag or fling crosses two directions at once. These pin the
 * CURRENT behavior so any future change to the thresholds or their ordering
 * in `SwipeMath.kt` is deliberate and goes through the spec (hard rule 4).
 */
class SwipeMathAuditTest {
    private val cardSize = Size(400f, 800f)
    private val density = 2f
    private val edgeX get() = cardSize.width * COMMIT_FRACTION_HORIZONTAL
    private val edgeY get() = cardSize.height * COMMIT_FRACTION_UP
    private val fling get() = FLING_VELOCITY_DP_S * density

    @Test
    fun `FR-13 vertical drags never resolve as a left or right verdict`() {
        // Short drag, mostly up but slightly left, below every threshold: snap back.
        assertNull(verdictFor(Offset(-edgeX + 1f, -(edgeY - 1f)), Offset.Zero, cardSize, density))
        // A small leftward lean with the up-drag past the SKIP threshold stays SKIP,
        // never DELETE: the horizontal component alone cannot resolve left.
        assertEquals(
            SwipeVerdict.SKIP,
            verdictFor(Offset(-edgeX / 2f, -(edgeY + 1f)), Offset.Zero, cardSize, density),
        )
        assertEquals(
            SwipeVerdict.SKIP,
            verdictFor(Offset(edgeX / 2f, -(edgeY + 1f)), Offset.Zero, cardSize, density),
        )
        // A fast horizontal flick below the fling threshold with a short vertical
        // drag still snaps back (neither axis commits).
        assertNull(
            verdictFor(
                Offset(-edgeX / 4f, -(edgeY / 4f)),
                Offset(-(fling - 1f), -(fling - 1f)),
                cardSize,
                density,
            ),
        )
        // A fast fling straight DOWN never commits anything (§5.6 has no down verdict).
        assertNull(verdictFor(Offset.Zero, Offset(0f, fling * 2f), cardSize, density))
    }

    @Test
    fun `FR-13 a diagonal drag past both thresholds previews and commits DELETE not SKIP`() {
        // U1: an up-left drag that crosses BOTH the 35 % width and the 25 % height
        // thresholds is decided DELETE — `previewVerdict` tests KEEP, then DELETE,
        // then SKIP, so the horizontal direction wins. Pinned so a spec change is
        // deliberate (audit finding U1).
        val upLeft = Offset(-(edgeX + 10f), -(edgeY + 10f))
        assertEquals(SwipeVerdict.DELETE, previewVerdict(upLeft, cardSize))
        assertEquals(SwipeVerdict.DELETE, verdictFor(upLeft, Offset.Zero, cardSize, density))
        // Symmetric for up-right: KEEP wins over SKIP.
        val upRight = Offset(edgeX + 10f, -(edgeY + 10f))
        assertEquals(SwipeVerdict.KEEP, previewVerdict(upRight, cardSize))
        assertEquals(SwipeVerdict.KEEP, verdictFor(upRight, Offset.Zero, cardSize, density))
    }

    @Test
    fun `FR-13 a diagonal fling is decided by its dominant velocity axis`() {
        // U2: both axes over the fling threshold; the dominant axis decides. A flick
        // a hair more horizontal than vertical commits DELETE even though it reads
        // as a mostly-up flick (audit finding U2); a hair more vertical commits SKIP.
        val mostlyUpMoreHorizontal = Offset(-(fling + 100f), -(fling + 50f))
        assertEquals(
            SwipeVerdict.DELETE,
            verdictFor(Offset.Zero, mostlyUpMoreHorizontal, cardSize, density),
        )
        val mostlyUpMoreVertical = Offset(-(fling + 50f), -(fling + 100f))
        assertEquals(
            SwipeVerdict.SKIP,
            verdictFor(Offset.Zero, mostlyUpMoreVertical, cardSize, density),
        )
        // Equal axes count as horizontal (abs(vx) >= abs(vy)), so a 45° up-left
        // fling commits DELETE.
        assertEquals(
            SwipeVerdict.DELETE,
            verdictFor(Offset.Zero, Offset(-fling, -fling), cardSize, density),
        )
    }
}
