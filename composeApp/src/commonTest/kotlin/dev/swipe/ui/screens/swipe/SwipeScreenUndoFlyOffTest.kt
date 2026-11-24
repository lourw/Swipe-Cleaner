// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import dev.swipe.ui.components.swipe.UNDO_RETURN_MS
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
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

    @Test
    fun `FR-15 an Undo tapped after its Decide goes out is sent straight through`() =
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
            val size = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * (COMMIT_FRACTION_HORIZONTAL + 0.1f), start.y))
                up()
            }
            // Let the flight land first: its Decide is out, so the UI's hold
            // window (release → dispatch) has closed. A tap now belongs to the
            // ViewModel's queue, not to the gate — it must arrive immediately.
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Decide("current-id", Decision.KEEP)), intents)
            onNodeWithContentDescription("Undo").performClick()
            assertEquals(
                listOf<SwipeIntent>(SwipeIntent.Decide("current-id", Decision.KEEP), SwipeIntent.Undo),
                intents,
            )
        }

    @Test
    fun `FR-15 a held Undo cannot leak into the next card`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            // Dynamic deck: the first card's commit is followed by a real card
            // change, the way the ViewModel answers a landed write.
            val states = MutableStateFlow(deckState().copy(canUndo = true))
            mainClock.autoAdvance = false
            setContent {
                SwipeTheme {
                    SwipeScreen(
                        state = states.collectAsState().value,
                        events = flowOf(),
                        onIntent = { intents.add(it) },
                        mediaLoader = FakeMediaLoader(),
                        haptics = FakeHaptics(),
                        reduceMotion = false,
                        onClose = {},
                        onOpenQueue = {},
                    )
                }
            }
            waitForIdle()
            val size = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * (COMMIT_FRACTION_HORIZONTAL + 0.1f), start.y))
                up()
            }
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() / 2L)
            // Held mid-flight (WP-9.10).
            onNodeWithContentDescription("Undo").performClick()
            assertEquals(emptyList(), intents)
            // The flight lands: Decide, then the forwarded Undo.
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            assertEquals(
                listOf<SwipeIntent>(
                    SwipeIntent.Decide("current-id", Decision.KEEP),
                    SwipeIntent.Undo,
                ),
                intents,
            )
            // The deck moves on, as a landed write does — and the second card
            // makes its own fly-off (button path, §5.6).
            states.value = SwipeUiState(current = card("next-id"), next = card("third-id"), canUndo = true)
            waitForIdle()
            onNodeWithContentDescription("Delete").performClick()
            mainClock.advanceTimeBy(NUDGE_MS.toLong() + FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            // The next card commits its own verdict with NO second Undo riding
            // behind it: the held tap was consumed by its own flight, and a
            // stale one rewinding this decision is the leak this pins out.
            assertEquals(
                listOf<SwipeIntent>(
                    SwipeIntent.Decide("current-id", Decision.KEEP),
                    SwipeIntent.Undo,
                    SwipeIntent.Decide("next-id", Decision.DELETE),
                ),
                intents,
            )
        }

    @Test
    fun `FR-15 the hold re-arms when the same card flies off again after a return`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            // The events channel shape the ViewModel hands the screen in
            // production: trySend never suspends, so the frame-driven
            // collector cannot deadlock the manual test clock.
            val events = Channel<SwipeEvent>()
            mainClock.autoAdvance = false
            setContent {
                TestScreen(
                    deckState().copy(canUndo = true),
                    events = events.receiveAsFlow(),
                    onIntent = { intents.add(it) },
                )
            }
            waitForIdle()
            val size = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * (COMMIT_FRACTION_HORIZONTAL + 0.1f), start.y))
                up()
            }
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() / 2L)
            onNodeWithContentDescription("Undo").performClick()
            // Flight one lands: Decide, then the forwarded Undo.
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            assertEquals(
                listOf<SwipeIntent>(
                    SwipeIntent.Decide("current-id", Decision.KEEP),
                    SwipeIntent.Undo,
                ),
                intents,
            )
            // The VM sends the card back (FR-15 undo return / FR-22 refused
            // favorite): same card, same id, back at rest.
            events.trySend(SwipeEvent.ReturnCard(Decision.KEEP))
            mainClock.advanceTimeBy(UNDO_RETURN_MS.toLong() + 50L)
            waitForIdle()
            // A second fly-off of the SAME card opens the window again: a tap
            // mid-flight is held, not sent early to rewind some older step.
            onNodeWithTag(SWIPE_CARD_TEST_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * (COMMIT_FRACTION_HORIZONTAL + 0.1f), start.y))
                up()
            }
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() / 2L)
            onNodeWithContentDescription("Undo").performClick()
            assertEquals(
                listOf<SwipeIntent>(
                    SwipeIntent.Decide("current-id", Decision.KEEP),
                    SwipeIntent.Undo,
                ),
                intents,
            )
            // And it rides the second flight's Decide, in order.
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            assertEquals(
                listOf<SwipeIntent>(
                    SwipeIntent.Decide("current-id", Decision.KEEP),
                    SwipeIntent.Undo,
                    SwipeIntent.Decide("current-id", Decision.KEEP),
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
