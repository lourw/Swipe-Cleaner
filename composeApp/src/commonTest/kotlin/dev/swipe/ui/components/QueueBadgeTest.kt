package dev.swipe.ui.components

import dev.swipe.ui.theme.SwipeComponentSizes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Badge copy rules: cap at 99+ per REQUIREMENTS §5.7. */
class QueueBadgeTest {
    private val sizes = SwipeComponentSizes()

    @Test
    fun `5-7 badge shows exact count up to 99`() {
        assertEquals("0", badgeLabel(0, sizes.badgeMaxShown))
        assertEquals("42", badgeLabel(42, sizes.badgeMaxShown))
        assertEquals("99", badgeLabel(99, sizes.badgeMaxShown))
    }

    @Test
    fun `5-7 badge shows 99+ above 99`() {
        assertEquals("99+", badgeLabel(100, sizes.badgeMaxShown))
        assertEquals("99+", badgeLabel(1234, sizes.badgeMaxShown))
    }

    @Test
    fun `5-7 badge description ends with a queue noun`() {
        assertTrue(badgeDescription(7, sizes.badgeMaxShown).endsWith("queued"))
    }
}
