// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.home

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** WP-1.7 placeholder Home: title, Start shuffle, and the FR-2 Limited banner. */
class HomeScreenTest {
    @Test
    fun `5-8 home placeholder shows title and start shuffle`() =
        runComposeUiTest {
            var taps = 0
            setContent {
                SwipeTheme {
                    HomeScreen(onStartShuffle = { taps++ })
                }
            }
            onNodeWithText("Swipe Cleaner").assertExists()
            onNodeWithText("Start shuffle").performClick()
            assertEquals(1, taps)
        }

    @Test
    fun `FR-2 home shows the limited banner with the item count`() =
        runComposeUiTest {
            var manageTaps = 0
            setContent {
                SwipeTheme {
                    HomeScreen(
                        onStartShuffle = {},
                        limitedItemCount = 7,
                        onManageLimitedAccess = { manageTaps++ },
                    )
                }
            }
            onNodeWithText("You've given access to 7 items.").assertExists()
            onNodeWithText("Manage").performClick()
            assertEquals(1, manageTaps)
        }

    @Test
    fun `FR-2 home hides the limited banner with full access`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    HomeScreen(onStartShuffle = {})
                }
            }
            onNodeWithText("You've given access to 7 items.").assertDoesNotExist()
        }
}
