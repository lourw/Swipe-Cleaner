// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.summary

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The FR-26 delete-success screen: the `display` count with the
 * `delete`-colored "Deleted" label, and the "Back home" CTA (§5.8).
 */
class DeleteSuccessScreenTest {
    @Test
    fun `FR-26 success screen shows the deleted count and Back home`() =
        runComposeUiTest {
            var homeTaps = 0
            setContent {
                SwipeTheme {
                    DeleteSuccessScreen(deleted = 4, onBackHome = { homeTaps += 1 })
                }
            }
            onNodeWithText("4").assertExists()
            onNodeWithText("Deleted").assertExists()
            onNodeWithContentDescription("Back home").performClick()
            waitForIdle()
            assertEquals(1, homeTaps)
        }

    @Test
    fun `FR-26 the count rides the route not the queue`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    DeleteSuccessScreen(deleted = 128, onBackHome = {})
                }
            }
            onNodeWithText("128").assertExists()
        }
}
