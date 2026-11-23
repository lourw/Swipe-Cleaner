// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.summary

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.assertAllClickablesLabeled
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

    @Test
    fun `FR-26 a partial batch adds the couldn't-be-found line`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    DeleteSuccessScreen(deleted = 2, notFound = 3, onBackHome = {})
                }
            }
            onNodeWithText("2").assertExists()
            onNodeWithText("Deleted").assertExists()
            onNodeWithText("3 couldn't be found").assertExists()
        }

    @Test
    fun `FR-26 a fully deleted batch shows no couldn't-be-found line`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    DeleteSuccessScreen(deleted = 4, onBackHome = {})
                }
            }
            onNodeWithText("4").assertExists()
            // The default notFound = 0 keeps the screen exactly as before.
            onNodeWithText("0 couldn't be found").assertDoesNotExist()
        }

    @Test
    fun `NFR-8 every screen's clickable nodes have content descriptions`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    DeleteSuccessScreen(deleted = 4, onBackHome = {})
                }
            }
            waitForIdle()
            assertAllClickablesLabeled()
        }
}
