package dev.swipe.ui.components

import dev.swipe.ui.theme.SwipeComponentSizes
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the §5.7 component numbers so gallery drift is caught by tests. */
class ComponentSizesTest {
    private val sizes = SwipeComponentSizes()

    @Test
    fun `§5.7 primary button pill is 52dp`() {
        assertEquals(BUTTON_HEIGHT, sizes.primaryButtonHeight.value)
    }

    @Test
    fun `§5.7 badge minimum is 20dp and caps display at 99`() {
        assertEquals(BADGE_SIZE, sizes.badgeMinSize.value)
        assertEquals(99, sizes.badgeMaxShown)
    }

    @Test
    fun `§5.7 banner keeps a 4dp leading tone bar and 44dp min height`() {
        assertEquals(BANNER_BAR, sizes.bannerLeadingBarWidth.value)
        assertEquals(TOUCH_TARGET_MIN, sizes.bannerMinHeight.value)
    }

    @Test
    fun `§5.3 system_default icon is 17sp`() {
        assertEquals(SYSTEM_ICON_SIZE, sizes.systemDefaultIconSize.value)
    }

    @Test
    fun `§5.7 disabled alpha applied to disabled component content`() {
        assertEquals(0.4f, sizes.disabledAlpha)
    }

    @Test
    fun `§5.3 verdict-tinted fills use 16% alpha`() {
        assertEquals(0.16f, sizes.tintedFillAlpha)
    }

    @Test
    fun `§5.7 toast fills 90 percent of its track`() {
        assertEquals(0.9f, sizes.toastWidthFraction)
    }
}

private const val BUTTON_HEIGHT = 52f
private const val BADGE_SIZE = 20f
private const val BANNER_BAR = 4f
private const val TOUCH_TARGET_MIN = 44f
private const val SYSTEM_ICON_SIZE = 17f
