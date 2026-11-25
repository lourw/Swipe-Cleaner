package dev.swipe.ui.screens.swipe

import androidx.compose.ui.layout.ContentScale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §5.6 "Double-tap: toggles between aspect-fit (default) and aspect-fill":
 * fill means aspect-fill — [ContentScale.Crop], never the stretching
 * `FillBounds` (WP-9.8).
 */
class CardContentScaleTest {
    @Test
    fun `5-6 double-tap fill is aspect-fill, not a stretched frame`() {
        assertEquals(ContentScale.Crop, cardContentScale(fillMode = true))
    }

    @Test
    fun `5-6 default card is aspect-fit`() {
        assertEquals(ContentScale.Fit, cardContentScale(fillMode = false))
    }
}
