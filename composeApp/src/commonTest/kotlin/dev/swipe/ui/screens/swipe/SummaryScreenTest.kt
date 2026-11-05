// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

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
 * The FR-21 session summary (§5.8): the three verdict-colored counts and the
 * queue-aware CTA — "Review N to delete" when anything is queued, "Back home"
 * when the deck closed with an empty queue.
 */
class SummaryScreenTest {
    @Test
    fun `FR-21 summary shows kept queued skipped`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    SummaryScreen(kept = 92, queued = 18, skipped = 4, onBackHome = {}, onReviewQueue = {})
                }
            }
            onNodeWithText("92").assertExists()
            onNodeWithText("Kept").assertExists()
            onNodeWithText("18").assertExists()
            onNodeWithText("Queued").assertExists()
            onNodeWithText("4").assertExists()
            onNodeWithText("Skipped").assertExists()
        }

    @Test
    fun `FR-21 a non-empty queue offers the review CTA`() =
        runComposeUiTest {
            var reviewed = 0
            setContent {
                SwipeTheme {
                    SummaryScreen(
                        kept = 3,
                        queued = 2,
                        skipped = 1,
                        onBackHome = {},
                        onReviewQueue = { reviewed += 1 },
                    )
                }
            }
            onNodeWithContentDescription("Review 2 to delete").performClick()
            waitForIdle()
            assertEquals(1, reviewed)
            onNodeWithContentDescription("Back home").assertDoesNotExist()
        }

    @Test
    fun `FR-21 an empty queue sends the user back home`() =
        runComposeUiTest {
            var home = 0
            setContent {
                SwipeTheme {
                    SummaryScreen(
                        kept = 3,
                        queued = 0,
                        skipped = 1,
                        onBackHome = { home += 1 },
                        onReviewQueue = {},
                    )
                }
            }
            onNodeWithContentDescription("Review 0 to delete").assertDoesNotExist()
            onNodeWithContentDescription("Back home").performClick()
            waitForIdle()
            assertEquals(1, home)
        }

    @Test
    fun `NFR-8 every screen's clickable nodes have content descriptions`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    SummaryScreen(kept = 92, queued = 18, skipped = 4, onBackHome = {}, onReviewQueue = {})
                }
            }
            waitForIdle()
            assertAllClickablesLabeled()
        }
}
