// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * §5.7 Toast: renders its text, announces it as an assertive live region, and
 * auto-dismisses after 3 s — advanced virtually through the test scheduler,
 * so the test runs instantly without a real delay (FR-25 undo toast).
 */
class ToastTest {
    @Test
    fun `5-7 toast renders its text with assertive live region`() =
        runComposeUiTest {
            setContent { Toast(text = "Undone", autoDismissDelay = Duration.INFINITE) }
            onNodeWithText("Undone").assertExists()
            val node = onNodeWithContentDescription("Undone").fetchSemanticsNode()
            assertEquals(LiveRegionMode.Assertive, node.config[SemanticsProperties.LiveRegion])
        }

    @Test
    fun `5-7 toast auto-dismisses after 3 s`() =
        runComposeUiTest {
            setContent { Toast(text = "Undone", autoDismissDelay = 3000.milliseconds) }
            onNodeWithText("Undone").fetchSemanticsNode()
            mainClock.advanceTimeBy(3000)
            waitForIdle()
            onAllNodesWithText("Undone").assertCountEquals(0)
        }

    @Test
    fun `5-7 toast is still visible 1 second after it appears`() =
        runComposeUiTest {
            setContent { Toast(text = "Undone", autoDismissDelay = 3000.milliseconds) }
            onNodeWithText("Undone").fetchSemanticsNode()
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            onAllNodesWithText("Undone").assertCountEquals(1)
        }
}
