package dev.swipe.ui.components

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §5.7 Toast: renders its text, announces it as an assertive live region, and
 * auto-dismisses after 3 s — advanced virtually through the frame clock, so
 * the test runs instantly without a real delay (FR-25 undo toast).
 */
class ToastTest {
    @Test
    fun `§5.7 toast renders its text with assertive live region`() = runComposeUiTest {
        setContent { Toast(text = "Undone", autoDismissDelay = Duration.INFINITE) }
        val node = onNodeWithText("Undone").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Assertive, node.config[SemanticsProperties.LiveRegion])
        assertEquals("Undone", node.config[SemanticsProperties.ContentDescription].single())
    }

    @Test
    fun `§5.7 toast auto dismisses 3 seconds after it appears`() = runComposeUiTest {
        setContent { Toast(text = "Undone", autoDismissDelay = 3000.milliseconds) }
        onNodeWithText("Undone").fetchSemanticsNode()
        mainClock.advanceTimeBy(3000)
        waitForIdle()
        onAllNodesWithText("Undone").assertCountEquals(0)
    }

    @Test
    fun `§5.7 toast is still visible 1 second after it appears`() = runComposeUiTest {
        setContent { Toast(text = "Undone", autoDismissDelay = 3000.milliseconds) }
        onNodeWithText("Undone").fetchSemanticsNode()
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        onAllNodesWithText("Undone").assertCountEquals(1)
    }
}
