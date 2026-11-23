// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WP-8.9 / FR-22 / NFR-8: the favourite-delete confirmation card. Layout,
 * touch targets, colour roles, and accessibility; the deck wiring and the U3
 * gesture gating live in SwipeScreenTest.
 */
class FavoriteConfirmCardTest {
    @Test
    fun `FR-22 the card states the question and one supporting line`() =
        runComposeUiTest {
            setContent { SwipeTheme { FavoriteConfirmCard(onCancel = {}, onDelete = {}) } }
            onNodeWithText("Delete this favorite?").assertExists()
            onNodeWithText("Queues it with the items waiting to be deleted.").assertExists()
        }

    @Test
    fun `FR-22 Cancel is left of Delete and both meet the 44 dp target at equal size`() =
        runComposeUiTest {
            setContent { SwipeTheme { FavoriteConfirmCard(onCancel = {}, onDelete = {}) } }
            val cancel = onNodeWithContentDescription("Cancel, keep this photo").fetchSemanticsNode()
            val delete = onNodeWithContentDescription("Delete favorite").fetchSemanticsNode()
            // Order: the Cancel cell sits entirely left of the Delete cell.
            val cancelBounds = cancel.boundsInWindow
            val deleteBounds = delete.boundsInWindow
            assertTrue(
                cancelBounds.right <= deleteBounds.left + 1f,
                "Cancel must sit left of Delete, got $cancelBounds vs $deleteBounds",
            )
            assertEquals(cancel.size, delete.size)
            val width: Dp = with(density) { cancel.size.width.toDp() }
            val height: Dp = with(density) { cancel.size.height.toDp() }
            assertTrue(
                height >= 44.dp && width >= 44.dp,
                "confirm buttons must meet the §5.5 44 dp target, got $width × $height",
            )
        }

    @Test
    fun `FR-22 Cancel and Delete run exactly their own callbacks`() =
        runComposeUiTest {
            var cancels = 0
            var deletes = 0
            setContent {
                SwipeTheme {
                    FavoriteConfirmCard(
                        onCancel = { cancels += 1 },
                        onDelete = { deletes += 1 },
                    )
                }
            }
            onNodeWithContentDescription("Cancel, keep this photo").performClick()
            assertEquals(1, cancels)
            assertEquals(0, deletes)
            onNodeWithContentDescription("Delete favorite").performClick()
            assertEquals(1, cancels)
            assertEquals(1, deletes)
        }

    @Test
    fun `NFR-8 the card announces the title and the buttons carry their full labels`() =
        runComposeUiTest {
            setContent { SwipeTheme { FavoriteConfirmCard(onCancel = {}, onDelete = {}) } }
            // The card container carries the title (VoiceOver reads it on
            // appear) and the buttons stay individually labelled (NFR-8).
            onNodeWithContentDescription("Delete this favorite?").assertIsDisplayed()
            onNodeWithContentDescription("Cancel, keep this photo").assertIsDisplayed()
            onNodeWithContentDescription("Delete favorite").assertIsDisplayed()
            // The visible captions match the derived accessibility labels.
            onNodeWithContentDescription("Cancel, keep this photo")
                .assertTextEquals("Cancel")
            onNodeWithContentDescription("Delete favorite")
                .assertTextEquals("Delete")
        }

    @Test
    fun `NFR-8 with Reduce Motion the card is fully opaque immediately`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    FavoriteConfirmCard(
                        onCancel = {},
                        onDelete = {},
                        reduceMotion = true,
                    )
                }
            }
            // No fade to wait for: the entrance alpha reads 1 in the first
            // frame the card exists (the hook mirrors the rotation audit's).
            val alpha =
                onNodeWithContentDescription("Delete this favorite?")
                    .fetchSemanticsNode()
                    .config[FavoriteConfirmAlphaSemanticsPropertyKey]
            assertEquals(1f, alpha)
        }

    @Test
    fun `NFR-8 without Reduce Motion the card fades in and settles opaque`() =
        runComposeUiTest {
            // Drive frames by hand so the mid-fade and the settled state can
            // be checked apart (the SwipeCardTest pattern).
            mainClock.autoAdvance = false
            setContent {
                SwipeTheme {
                    FavoriteConfirmCard(
                        onCancel = {},
                        onDelete = {},
                    )
                }
            }
            val node = onNodeWithContentDescription("Delete this favorite?")
            // One frame starts the entrance; mid-fade the alpha is strictly
            // between 0 and 1; after the full duration it has settled at 1.
            mainClock.advanceTimeBy(1L)
            mainClock.advanceTimeBy(FAVORITE_CONFIRM_ENTRANCE_MS / 2L)
            val partial = node.fetchSemanticsNode().config[FavoriteConfirmAlphaSemanticsPropertyKey]
            assertTrue(partial > 0f && partial < 1f, "expected a fade in progress, got $partial")
            mainClock.advanceTimeBy(FAVORITE_CONFIRM_ENTRANCE_MS.toLong())
            val settled = node.fetchSemanticsNode().config[FavoriteConfirmAlphaSemanticsPropertyKey]
            assertEquals(1f, settled)
        }

    @Test
    fun `FR-22 the card never dismisses itself on a timer`() =
        runComposeUiTest {
            setContent { SwipeTheme { FavoriteConfirmCard(onCancel = {}, onDelete = {}) } }
            // Well past the toast's 3 s fuse: still here, both actions live.
            mainClock.advanceTimeBy(5_000)
            onNodeWithText("Delete this favorite?").assertExists()
            onNodeWithContentDescription("Cancel, keep this photo").assertExists()
            onNodeWithContentDescription("Delete favorite").assertExists()
        }
}
