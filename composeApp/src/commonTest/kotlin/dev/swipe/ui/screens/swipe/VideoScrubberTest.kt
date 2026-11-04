package dev.swipe.ui.screens.swipe

import kotlin.test.Test
import kotlin.test.assertEquals

/** FR-16: the scrubber's drag-position contract of [scrubFractionAt]. */
class VideoScrubberTest {
    @Test
    fun `FR-16 scrub fraction divides drag x by track width`() {
        assertEquals(0f, scrubFractionAt(0f, 100f))
        assertEquals(0.25f, scrubFractionAt(25f, 100f))
        assertEquals(0.5f, scrubFractionAt(50f, 100f))
        assertEquals(1f, scrubFractionAt(100f, 100f))
    }

    @Test
    fun `FR-16 scrub fraction clamps outside the track`() {
        assertEquals(0f, scrubFractionAt(-30f, 100f))
        assertEquals(1f, scrubFractionAt(130f, 100f))
    }

    @Test
    fun `FR-16 scrub fraction guards zero and non-finite widths`() {
        // A pre-layout drag (width 0) must never divide by zero.
        assertEquals(0f, scrubFractionAt(10f, 0f))
        assertEquals(0f, scrubFractionAt(10f, -1f))
        assertEquals(0f, scrubFractionAt(Float.NaN, 100f))
        assertEquals(0f, scrubFractionAt(10f, Float.NaN))
        assertEquals(0f, scrubFractionAt(10f, Float.POSITIVE_INFINITY))
    }
}
