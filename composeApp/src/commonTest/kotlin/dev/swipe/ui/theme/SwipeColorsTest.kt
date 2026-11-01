package dev.swipe.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeColorsTest {
    @Test
    fun `verdict colors match REQUIREMENTS 5-3`() {
        assertEquals(Color(0xFF30D158), DarkSwipeColors.keep)
        assertEquals(Color(0xFFFF453A), DarkSwipeColors.delete)
        assertEquals(Color(0xFF8E8E93), DarkSwipeColors.skip)
    }

    @Test
    fun `verdict colors are distinct from every other token`() {
        val verdicts = setOf(DarkSwipeColors.keep, DarkSwipeColors.delete, DarkSwipeColors.skip)
        val others =
            with(DarkSwipeColors) {
                listOf(
                    bg,
                    surface,
                    surfaceRaised,
                    border,
                    scrim,
                    textPrimary,
                    textSecondary,
                    textTertiary,
                    accent,
                    warning,
                )
            }
        assertEquals(3, verdicts.size)
        assertEquals(emptySet(), verdicts intersect others.toSet())
    }
}
