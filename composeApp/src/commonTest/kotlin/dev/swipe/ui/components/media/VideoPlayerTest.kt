package dev.swipe.ui.components.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** FR-16: the scrubber fraction contract of [videoProgressFraction]. */
class VideoPlayerTest {
    @Test
    fun `FR-16 progress fraction divides position by duration`() {
        assertEquals(0f, videoProgressFraction(0.0, 10.0))
        assertEquals(0.5f, videoProgressFraction(3.0, 6.0))
        assertEquals(0.75f, videoProgressFraction(1.5, 2.0))
    }

    @Test
    fun `FR-16 progress fraction clamps the end of the track`() {
        assertEquals(1f, videoProgressFraction(6.0, 6.0))
        assertEquals(1f, videoProgressFraction(6.1, 6.0)) // a tick may land past the end before the seek-to-zero
    }

    @Test
    fun `FR-16 progress fraction guards zero unknown and non-finite durations`() {
        assertEquals(0f, videoProgressFraction(3.0, 0.0))
        assertEquals(0f, videoProgressFraction(3.0, -1.0))
        // CMTime marks unknown durations NaN; infinite shows up on live sources.
        assertEquals(0f, videoProgressFraction(3.0, Double.NaN))
        assertEquals(0f, videoProgressFraction(3.0, Double.POSITIVE_INFINITY))
        assertEquals(0f, videoProgressFraction(Double.NaN, 6.0))
    }
}
