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

/**
 * §5.7 Toast as pure presentation (WP-9.3): it renders its text, announces it
 * as an assertive live region, and stays for as long as its caller renders
 * it — the 3 s fuse and the one placement belong to [ToastHost] (see
 * [ToastHostTest]).
 */
class ToastTest {
    @Test
    fun `5-7 toast renders its text with assertive live region`() =
        runComposeUiTest {
            setContent { Toast(text = "Undone") }
            onNodeWithText("Undone").assertExists()
            val node = onNodeWithContentDescription("Undone").fetchSemanticsNode()
            assertEquals(LiveRegionMode.Assertive, node.config[SemanticsProperties.LiveRegion])
        }

    @Test
    fun `5-7 toast stays until its caller stops rendering it`() =
        runComposeUiTest {
            setContent { Toast(text = "Undone") }
            onNodeWithText("Undone").fetchSemanticsNode()
            // The pill owns no timer (WP-9.3): long past the old 3 s fuse it
            // is still on screen — only its caller can take it away.
            mainClock.advanceTimeBy(10_000L)
            waitForIdle()
            onAllNodesWithText("Undone").assertCountEquals(1)
        }
}
