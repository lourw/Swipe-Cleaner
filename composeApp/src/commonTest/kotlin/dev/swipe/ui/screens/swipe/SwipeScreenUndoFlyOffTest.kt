// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import dev.swipe.ui.components.swipe.COMMIT_FRACTION_HORIZONTAL
import dev.swipe.ui.components.swipe.FLY_OFF_MS
import dev.swipe.ui.components.swipe.NUDGE_MS
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FR-15 (WP-9.10) at the UI layer: an Undo tapped while the card is mid
 * **fly-off** is held by the deck ([rememberUndoFlyOffHold]) and forwarded
 * immediately after that flight's own Decide intent — never before it, so the
 * ViewModel queues it from the right decision and the card comes back when the
 * commit lands. Both fly-off paths are pinned: the gesture release and the
 * §5.6 button nudge + fly-off.
 */
class SwipeScreenUndoFlyOffTest {
    private fun card(id: String) =
        CardState(
            id = id,
            type = MediaType.PHOTO,
            createdAt = null,
            durationMs = null,
            isFavorite = false,
        )

    private fun deckState() = SwipeUiState(current = card("current-id"), next = card("next-id"))

    @Test
    fun `FR-15 an Undo tapped mid-fly-off is forwarded right after the commit`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            // Drive frames by hand so the Undo tap lands mid-flight, before the
            // gesture's own commit (WP-9.10: the tap belongs to that swipe).
            mainClock.autoAdvance = false
            setContent {
                TestScreen(
                    deckState().copy(canUndo = true),
                    onIntent = { intents.add(it) },
                )
            }
            waitForIdle()
            val size = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            // Drag past the KEEP threshold and release: the card flies off.
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * (COMMIT_FRACTION_HORIZONTAL + 0.1f), start.y))
                up()
            }
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() / 2L)
            // Mid-flight the Undo is held — the Decide has not even gone out yet,
            // so firing now would rewind an older step instead.
            onNodeWithContentDescription("Undo").performClick()
            assertEquals(emptyList(), intents)
            // When the flight ends the commit goes out, and the held Undo rides
            // right behind it (order pinned: the ViewModel queues it from there).
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            assertEquals(
                listOf<SwipeIntent>(
                    SwipeIntent.Decide("current-id", Decision.KEEP),
                    SwipeIntent.Undo,
                ),
                intents,
            )
        }

    @Test
    fun `FR-15 an Undo tapped during a button fly-off is forwarded right after the commit`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            mainClock.autoAdvance = false
            setContent {
                TestScreen(
                    deckState().copy(canUndo = true),
                    onIntent = { intents.add(it) },
                )
            }
            waitForIdle()
            onNodeWithContentDescription("Delete").performClick()
            mainClock.advanceTimeBy(NUDGE_MS.toLong() + FLY_OFF_MS.toLong() / 2L)
            // Mid nudge + fly-off: held.
            onNodeWithContentDescription("Undo").performClick()
            assertEquals(emptyList(), intents)
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            assertEquals(
                listOf<SwipeIntent>(
                    SwipeIntent.Decide("current-id", Decision.DELETE),
                    SwipeIntent.Undo,
                ),
                intents,
            )
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
