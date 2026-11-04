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

    @Test
    fun `FR-16 seek position maps the scrub fraction back to seconds`() {
        assertEquals(3.0, seekPositionSeconds(0.5f, 6.0))
        assertEquals(0.0, seekPositionSeconds(0f, 6.0))
        assertEquals(6.0, seekPositionSeconds(1f, 6.0), 0.0001)
    }

    @Test
    fun `FR-16 seek position clamps and guards like the progress fraction`() {
        // A drag can ask for the very edges; the fraction clamps first.
        assertEquals(0.0, seekPositionSeconds(-0.4f, 6.0))
        assertEquals(6.0, seekPositionSeconds(1.4f, 6.0), 0.0001)
        // Unknown/empty durations must not divide into NaN.
        assertEquals(0.0, seekPositionSeconds(0.5f, 0.0))
        assertEquals(0.0, seekPositionSeconds(0.5f, Double.NaN))
        assertEquals(0.0, seekPositionSeconds(0.5f, Double.POSITIVE_INFINITY))
    }
}
