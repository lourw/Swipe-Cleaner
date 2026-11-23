// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import dev.swipe.domain.model.Progress
import dev.swipe.domain.model.SessionSummary
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
import dev.swipe.ui.components.swipe.NUDGE_MS
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The WP-2.3 assembled deck screen: the §5.6 button paths commit their intent
 * after the fly-off (FR-12–14) with the right haptic, Undo rides the FR-15
 * enabled/disabled state, the §5.7 chrome carries the FR-19 count and the
 * FR-10 badge, the toasts surface (FR-15, FR-22, NFR-7), the empty and
 * finished states replace the deck (§5.8, FR-21), and each on-screen card
 * still collects exactly one image at the shared full target (NFR-1, NFR-3).
 */
class SwipeScreenTest {
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
    fun `FR-12 Keep button flies the card off then sends the Keep intent`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            val haptics = FakeHaptics()
            setContent {
                TestScreen(
                    deckState(),
                    onIntent = { intents.add(it) },
                    haptics = haptics,
                )
            }
            onNodeWithContentDescription("Keep").performClick()
            assertEquals(emptyList(), intents) // the flight runs first (§5.6)
            waitForIdle()
            mainClock.advanceTimeBy((NUDGE_MS + FLY_OFF_MS).toLong() + 50L)
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Decide("current-id", Decision.KEEP)), intents)
            assertEquals(listOf(FakeHaptics.Event.IMPACT_LIGHT), haptics.events)
        }

    @Test
    fun `FR-13 Delete button commits with the medium impact`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            val haptics = FakeHaptics()
            setContent {
                TestScreen(
                    deckState(),
                    onIntent = { intents.add(it) },
                    haptics = haptics,
                )
            }
            onNodeWithContentDescription("Delete").performClick()
            waitForIdle()
            mainClock.advanceTimeBy((NUDGE_MS + FLY_OFF_MS).toLong() + 50L)
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Decide("current-id", Decision.DELETE)), intents)
            assertEquals(listOf(FakeHaptics.Event.IMPACT_MEDIUM), haptics.events)
        }

    @Test
    fun `FR-14 Skip button sends the Skip intent`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            val haptics = FakeHaptics()
            setContent {
                TestScreen(
                    deckState(),
                    onIntent = { intents.add(it) },
                    haptics = haptics,
                )
            }
            onNodeWithContentDescription("Skip").performClick()
            waitForIdle()
            mainClock.advanceTimeBy((NUDGE_MS + FLY_OFF_MS).toLong() + 50L)
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Decide("current-id", Decision.SKIP)), intents)
            assertEquals(listOf(FakeHaptics.Event.IMPACT_LIGHT), haptics.events)
        }

    @Test
    fun `FR-13 a button tap during a card's fly-off decides nothing`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            // Drive frames by hand so the tap lands mid-flight, before the
            // gesture's own commit (the same window the bug lived in).
            mainClock.autoAdvance = false
            setContent {
                TestScreen(deckState(), onIntent = { intents.add(it) })
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
            // Mid-flight the Keep button does nothing at all…
            onNodeWithContentDescription("Keep").performClick()
            // …and when the gesture commit lands it names the swiped card —
            // exactly one intent for the card, never one per tap.
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Decide("current-id", Decision.KEEP)), intents)
        }

    @Test
    fun `FR-15 undo is disabled while the deck's stack is empty`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(deckState(), onIntent = { intents.add(it) })
            }
            onNodeWithContentDescription("Undo").assertIsNotEnabled()
        }

    @Test
    fun `FR-15 enabled undo sends the Undo intent`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(
                    deckState().copy(canUndo = true),
                    onIntent = { intents.add(it) },
                )
            }
            onNodeWithContentDescription("Undo").assertIsEnabled()
            onNodeWithContentDescription("Undo").performClick()
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Undo), intents)
        }

    @Test
    fun `FR-19 top bar shows reviewed over total with thousands grouping`() =
        runComposeUiTest {
            setContent {
                TestScreen(
                    deckState().copy(progress = Progress(reviewed = 128, total = 2340)),
                )
            }
            onNodeWithText("128 / 2,340").assertExists()
            onNodeWithContentDescription("128 of 2,340 reviewed").assertExists()
        }

    @Test
    fun `FR-10 queue badge shows the count and opens the queue`() =
        runComposeUiTest {
            var opened = 0
            setContent {
                TestScreen(
                    deckState().copy(queueCount = 3),
                    onOpenQueue = { opened += 1 },
                )
            }
            onNodeWithContentDescription("3 queued — open the queue").performClick()
            waitForIdle()
            assertEquals(1, opened)
        }

    @Test
    fun `FR-15 undone toast rides the screen and dismisses through the intent`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(
                    deckState().copy(toast = ToastMessage.Undone),
                    onIntent = { intents.add(it) },
                )
            }
            onNodeWithContentDescription("Undone").assertExists()
            mainClock.advanceTimeBy(3100L) // the §5.7 3 s auto-dismiss
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.DismissToast), intents)
        }

    @Test
    fun `WP-9-3 a plain deck toast fades on the host's three-second fuse`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(
                    deckState().copy(toast = ToastMessage.SaveFailed),
                    onIntent = { intents.add(it) },
                )
            }
            waitForIdle()
            onNodeWithContentDescription("Couldn't save — try again").assertExists()
            mainClock.advanceTimeBy(3100L)
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.DismissToast), intents)
        }

    @Test
    fun `NFR-7 save-failed toast surfaces the retry copy`() =
        runComposeUiTest {
            setContent {
                TestScreen(deckState().copy(toast = ToastMessage.SaveFailed))
            }
            onNodeWithContentDescription("Couldn't save — try again").assertExists()
        }

    @Test
    fun `5-8 empty state replaces the deck and Closes through Home`() =
        runComposeUiTest {
            var closed = 0
            setContent {
                TestScreen(SwipeUiState(), onClose = { closed += 1 })
            }
            onNodeWithText("Nothing left here 🎉").assertExists()
            onNodeWithContentDescription("Keep").assertDoesNotExist()
            onNodeWithText("Try another source").performClick()
            waitForIdle()
            assertEquals(1, closed)
        }

    @Test
    fun `FR-21 finished state holds the bare background for the summary route`() =
        runComposeUiTest {
            setContent {
                TestScreen(
                    SwipeUiState(finished = SessionSummary(kept = 3, queued = 1, skipped = 2)),
                )
            }
            onNodeWithText("Nothing left here 🎉").assertDoesNotExist()
            onNodeWithContentDescription("Keep").assertDoesNotExist()
        }

    @Test
    fun `6-6 exhausted deck requests no images`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            setContent {
                TestScreen(SwipeUiState(), mediaLoader = loader)
            }
            waitForIdle()
            assertEquals(emptyList(), loader.imageRequests)
        }

    @Test
    fun `NFR-8 every top bar and action bar control exposes its label`() =
        runComposeUiTest {
            setContent {
                TestScreen(deckState().copy(canUndo = true))
            }
            onNodeWithContentDescription("Close").assertExists()
            onNodeWithContentDescription("To delete queue").assertExists()
            onNodeWithContentDescription("Keep").assertExists()
            onNodeWithContentDescription("Delete").assertExists()
            onNodeWithContentDescription("Skip").assertExists()
            onNodeWithContentDescription("Undo").assertExists()
            onNodeWithContentDescription("Share").assertExists()
        }

    @Test
    fun `FR-32 share button sends only the Share intent`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            val haptics = FakeHaptics()
            setContent {
                TestScreen(deckState(), onIntent = { intents.add(it) }, haptics = haptics)
            }
            onNodeWithContentDescription("Share").assertIsEnabled()
            onNodeWithContentDescription("Share").performClick()
            waitForIdle()
            // No verdict, no fly-off commit, no verdict haptic — the card stays put.
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Share), intents)
            assertEquals(emptyList(), haptics.events)
        }

    @Test
    fun `FR-32 share button shows a disabled loading state while the media loads`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(deckState().copy(sharing = true), onIntent = { intents.add(it) })
            }
            onNodeWithContentDescription("Share").assertDoesNotExist()
            onNodeWithContentDescription("Preparing to share").assertIsNotEnabled()
            onNodeWithContentDescription("Preparing to share").performClick()
            waitForIdle()
            assertEquals(emptyList(), intents)
        }

    @Test
    fun `FR-32 share-failed toast surfaces the load failure`() =
        runComposeUiTest {
            setContent {
                TestScreen(deckState().copy(toast = ToastMessage.ShareFailed))
            }
            onNodeWithContentDescription("Couldn't load this item to share — try again").assertExists()
        }

    @Test
    fun `NFR-1 current and next cards collect their images at the full target`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            setContent {
                TestScreen(deckState(), mediaLoader = loader)
            }
            waitForIdle()
            // One request per on-screen card — composition order, the next
            // card under the top one — at the shared cache key the ViewModel
            // prefetches with (NFR-3).
            assertEquals(listOf("next-id", "current-id"), loader.imageRequests.map { it.first })
            assertEquals(
                listOf(MediaLoader.FULL_TARGET_PX, MediaLoader.FULL_TARGET_PX),
                loader.imageRequests.map { it.second },
            )
        }

    @Test
    fun `FR-11 the deck composes the current card with the next one underneath it`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            setContent {
                TestScreen(deckState(), mediaLoader = loader)
            }
            waitForIdle()
            // FR-11: two cards are on screen at once — the current item and
            // the next one already rendered underneath it (composition order,
            // back to front). The full-bleed layout of each card is pinned by
            // SafeAreaBoundsTest; the under-card scale/alpha by SwipeMathTest.
            assertEquals(setOf("current-id", "next-id"), loader.imageRequests.map { it.first }.toSet())
            assertEquals(2, loader.imageRequests.size)
        }

    @Test
    fun `NFR-3 recomposition does not add a second image collector per card`() =
        runComposeUiTest {
            val loader = FakeMediaLoader()
            val state = MutableStateFlow(deckState())
            setContent {
                SwipeTheme {
                    SwipeScreen(
                        state = state.collectAsState().value,
                        events = flowOf(),
                        onIntent = {},
                        mediaLoader = loader,
                        haptics = FakeHaptics(),
                        reduceMotion = false,
                        onClose = {},
                        onOpenQueue = {},
                    )
                }
            }
            waitForIdle()
            // An unrelated state change recomposes the screen; the collectors
            // are keyed on (id, targetPx, loader), so no new request happens.
            state.value = state.value.copy(toast = null, queueCount = 3)
            waitForIdle()
            assertEquals(2, loader.imageRequests.size)
        }

    @Test
    fun `FR-15 the card keeps its exact size when a toast appears`() =
        runComposeUiTest {
            val state = MutableStateFlow(deckState())
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
            // WP-9.3: the toast overlays the card instead of taking a row of
            // the deck column, so the "Undone" toast never shrinks the card
            // mid-animation (the bug it used to cause was visible on device).
            state.value = state.value.copy(toast = ToastMessage.Undone)
            waitForIdle()
            val after = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().boundsInWindow
            assertEquals(before, after)
        }

    @Test
    fun `FR-16 the card keeps its exact size across photo and video cards`() =
        runComposeUiTest {
            val state = MutableStateFlow(deckState())
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
                        // The iOS VideoPlayer `actual` needs the Koin cache,
                        // which no UI test runs (see VideoPlayerSlot.kt).
                        videoSlot = { _, _, _, _, _, _, _ -> },
                    )
                }
            }
            waitForIdle()
            val photo = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().boundsInWindow
            // WP-9.3: the scrubber row's height is reserved on every card, so
            // a video card swapping in for a photo card never resizes it.
            state.value =
                state.value.copy(
                    current =
                        CardState(
                            id = "current-id",
                            type = MediaType.VIDEO,
                            createdAt = null,
                            durationMs = 8_000L,
                            isFavorite = false,
                        ),
                )
            waitForIdle()
            val video = onNodeWithTag(SWIPE_CARD_TEST_TAG).fetchSemanticsNode().boundsInWindow
            assertEquals(photo, video)
        }

    /**
     * The assembled screen under the theme, with inert defaults for the
     * container-only wiring (the events flow, media loader, and callbacks):
     * each test swaps in only what it observes.
     */
    @Composable
    private fun TestScreen(
        state: SwipeUiState,
        events: Flow<SwipeEvent> = flowOf(),
        onIntent: (SwipeIntent) -> Unit = {},
        mediaLoader: MediaLoader = FakeMediaLoader(),
        haptics: Haptics = FakeHaptics(),
        onClose: () -> Unit = {},
        onOpenQueue: () -> Unit = {},
    ) {
        SwipeTheme {
            SwipeScreen(
                state = state,
                events = events,
                onIntent = onIntent,
                mediaLoader = mediaLoader,
                haptics = haptics,
                reduceMotion = false,
                onClose = onClose,
                onOpenQueue = onOpenQueue,
            )
        }
    }

    @Test
    fun `NFR-8 every screen's clickable nodes have content descriptions`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    TestScreen(deckState())
                }
            }
            waitForIdle()
            assertAllClickablesLabeled()
        }

    @Test
    fun `NFR-8 the deck card's custom actions commit like the buttons`() =
        runComposeUiTest {
            val intents = mutableListOf<SwipeIntent>()
            setContent {
                TestScreen(deckState(), onIntent = { intents.add(it) })
            }
            waitForIdle()
            val card =
                onNodeWithContentDescription(cardAccessibilityDescription(card("current-id")))
                    .fetchSemanticsNode()
            assertEquals(
                listOf("Keep", "Delete", "Skip"),
                card.config[SemanticsActions.CustomActions].map { it.label },
            )
            // Firing the VoiceOver action takes the SAME commit path as the
            // ActionBar button: flight first, intent after (§5.6, FR-13).
            card.config[SemanticsActions.CustomActions].first { it.label == "Delete" }.action()
            waitForIdle()
            mainClock.advanceTimeBy((NUDGE_MS + FLY_OFF_MS).toLong() + 50L)
            waitForIdle()
            assertEquals(listOf<SwipeIntent>(SwipeIntent.Decide("current-id", Decision.DELETE)), intents)
        }

    @Test
    fun `NFR-8 the card's Undo action appears only while an undo is pending`() =
        runComposeUiTest {
            setContent {
                TestScreen(deckState().copy(canUndo = true))
            }
            waitForIdle()
            val card =
                onNodeWithContentDescription(cardAccessibilityDescription(card("current-id")))
                    .fetchSemanticsNode()
            assertEquals(
                listOf("Keep", "Delete", "Skip", "Undo"),
                card.config[SemanticsActions.CustomActions].map { it.label },
            )
        }
}
