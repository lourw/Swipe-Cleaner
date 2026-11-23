// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.data.Haptics
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.MediaType
import dev.swipe.fakes.FakeHaptics
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeEvent
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.presentation.swipe.SwipeUiState
import dev.swipe.presentation.swipe.ToastMessage
import dev.swipe.ui.assertAllClickablesLabeled
import dev.swipe.ui.components.swipe.COMMIT_FRACTION_HORIZONTAL
import dev.swipe.ui.components.swipe.FLY_OFF_MS
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP-8.9 / FR-22 / U3: the favorite confirm card on the assembled deck. The
 * card's Delete and Cancel ride their intents, nothing is on a timer, a drag
 * started while it is up cancels it, the photo under confirmation is never
 * resized, and every clickable stays labeled. The card component itself is
 * covered by `FavoriteConfirmCardTest`; the ViewModel guards by
 * `SwipeViewModelSessionTest` and `SwipeViewModelAuditTest`.
 */
class SwipeScreenFavoriteConfirmTest {
    private fun card(id: String) =
        CardState(
            id = id,
            type = MediaType.PHOTO,
            createdAt = null,
            durationMs = null,
            isFavorite = true,
        )

    /** The deck with the favorite confirm up on the current card (FR-22). */
    private fun confirmState() =
        SwipeUiState(
            current = card("current-id"),
            next = card("next-id"),
            toast = ToastMessage.FavoriteConfirm,
            pendingFavoriteDelete = "current-id",
        )

    @Test
    fun `FR-22 favorite confirm card Delete names the pending card and nothing else fires`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(confirmState(), onIntent = { intents.add(it) })
            }
            // WP-8.9: the confirm is the compact card, not a toast — and its
            // labels no longer collide with the action bar (a second "Delete"
            // would make this single-node lookup throw).
            onNodeWithText("Delete this favorite?").assertExists()
            onNodeWithContentDescription("Delete").assertExists() // the action bar's
            onNodeWithContentDescription("Delete favorite").performClick()
            waitForIdle()
            // FR-13: the confirm names the pending card it was raised for.
            assertEquals(listOf<SwipeIntent>(SwipeIntent.ConfirmFavoriteDelete("current-id")), intents)
        }

    @Test
    fun `FR-22 favorite confirm card Cancel dismisses and records nothing`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(confirmState(), onIntent = { intents.add(it) })
            }
            onNodeWithContentDescription("Cancel, keep this photo").performClick()
            waitForIdle()
            // Cancel is DismissToast and nothing else — no verdict, no confirm.
            assertEquals(listOf<SwipeIntent>(SwipeIntent.DismissToast), intents)
        }

    @Test
    fun `WP-8-9 the favorite confirm card survives the host's three-second fuse`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(confirmState(), onIntent = { intents.add(it) })
            }
            mainClock.advanceTimeBy(3100L)
            waitForIdle()
            // §5.7: the confirm stays until the user acts or the deck moves
            // on; nothing about it is on a timer.
            onNodeWithText("Delete this favorite?").assertIsDisplayed()
            assertEquals(emptyList<SwipeIntent>(), intents)
        }

    @Test
    fun `U3 starting a drag while the confirm is up cancels it without deciding`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(confirmState(), onIntent = { intents.add(it) })
            }
            waitForIdle()
            // A short drag (under every threshold) on the pending card: the
            // finger moving is the cancel — DismissToast and nothing else.
            val size = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * 0.1f, start.y))
                up()
            }
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.DismissToast), intents)
        }

    @Test
    fun `U3 a right swipe over the confirm cancels it and keeps the card`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(confirmState(), onIntent = { intents.add(it) })
            }
            waitForIdle()
            // Right past the KEEP threshold: the drag start cancels the
            // confirm, and the swipe itself is an ordinary KEEP — the favorite
            // stays, so there is nothing to confirm.
            val size = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * (COMMIT_FRACTION_HORIZONTAL + 0.1f), start.y))
                up()
            }
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            assertEquals(
                listOf<SwipeIntent>(
                    SwipeIntent.DismissToast,
                    SwipeIntent.Decide("current-id", Decision.KEEP),
                ),
                intents,
            )
        }

    @Test
    fun `WP-8-9 the card keeps its exact size when the confirm appears`() =
        runComposeUiTest {
            val state =
                MutableStateFlow(
                    SwipeUiState(current = card("current-id"), next = card("next-id")),
                )
            setContent {
                SwipeTheme {
                    SwipeScreen(
                        state = state.collectAsState().value,
                        events = flowOf(),
                        onIntent = {},
                        mediaLoader = FakeMediaLoader(),
                        haptics = FakeHaptics(),
                        reduceMotion = false,
                        onClose = {},
                        onOpenQueue = {},
                    )
                }
            }
            waitForIdle()
            val before = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().boundsInWindow
            // The confirm card overlays the stage above the action bar, so
            // the photo it confirms is never resized (FR-22, §5.7).
            state.value =
                state.value.copy(
                    toast = ToastMessage.FavoriteConfirm,
                    pendingFavoriteDelete = "current-id",
                )
            waitForIdle()
            val after = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().boundsInWindow
            assertEquals(before, after)
        }

    @Test
    fun `FR-22 a confirm without a pending id falls back to the plain toast copy`() =
        runComposeUiTest {
            setContent {
                TestScreen(
                    SwipeUiState(
                        current = card("current-id"),
                        next = card("next-id"),
                        toast = ToastMessage.FavoriteConfirm,
                    ),
                )
            }
            waitForIdle()
            // Defensive edge (the ViewModel never emits this pairing): with no
            // pending id there is nothing to confirm, so the message rides a
            // plain auto-dismiss toast rather than a card whose Delete has no
            // subject.
            onNodeWithContentDescription("This is a favorite — delete anyway?").assertExists()
            mainClock.advanceTimeBy(3100L)
            waitForIdle()
        }

    @Test
    fun `NFR-8 every clickable stays labeled with the confirm card up`() =
        runComposeUiTest {
            setContent {
                TestScreen(confirmState())
            }
            waitForIdle()
            assertAllClickablesLabeled()
        }

    /**
     * The assembled screen under the theme (the [SwipeScreenTest] pattern):
     * inert defaults for the container-only wiring, each test swaps in only
     * what it observes.
     */
    @Composable
    private fun TestScreen(
        state: SwipeUiState,
        events: Flow<SwipeEvent> = flowOf(),
        onIntent: (SwipeIntent) -> Unit = {},
        mediaLoader: MediaLoader = FakeMediaLoader(),
        haptics: Haptics = FakeHaptics(),
    ) {
        SwipeTheme {
            SwipeScreen(
                state = state,
                events = events,
                onIntent = onIntent,
                mediaLoader = mediaLoader,
                haptics = haptics,
                reduceMotion = false,
                onClose = {},
                onOpenQueue = {},
            )
        }
    }
}
