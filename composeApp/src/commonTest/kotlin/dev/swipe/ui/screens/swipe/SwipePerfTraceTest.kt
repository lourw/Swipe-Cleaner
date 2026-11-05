// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import dev.swipe.data.LoadedImage
import dev.swipe.data.MediaLoader
import dev.swipe.data.PerfSpan
import dev.swipe.domain.model.MediaType
import dev.swipe.fakes.FakeHaptics
import dev.swipe.fakes.FakePerfTrace
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeUiState
import dev.swipe.ui.components.swipe.FLY_OFF_MS
import dev.swipe.ui.components.swipe.NUDGE_MS
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The WP-6.3 NFR-1 instrumentation (docs/perf.md): a card commit opens both
 * next-card spans, and the new top card's image emissions close them —
 * the first image the degraded span, the first full-quality one the full
 * span. Initial loads emit outside any commit and open nothing, and a card
 * that never serves the full image leaves that span open, which renders as
 * no interval in the trace rather than a bogus one.
 */
class SwipePerfTraceTest {
    private fun card(id: String) =
        CardState(
            id = id,
            type = MediaType.PHOTO,
            createdAt = null,
            durationMs = null,
            isFavorite = false,
        )

    private fun deckState() = SwipeUiState(current = card("current-id"), next = card("next-id"))

    /**
     * A [MediaLoader] serving the degraded bitmap, then — after [fullDelayMs]
     * on the test clock, so the two emissions land in different recompositions
     * the way the real iCloud-slow path separates them — the full one.
     * [serveFull] = false models the card that never gets its full-quality
     * image within the review.
     */
    private fun steppedLoader(
        fullDelayMs: Long = 200L,
        serveFull: Boolean = true,
    ): MediaLoader =
        object : MediaLoader {
            override fun image(
                id: String,
                targetPx: IntSize,
            ): Flow<LoadedImage> =
                flow {
                    emit(
                        LoadedImage(
                            ImageBitmap(width = 1, height = 1),
                            isDegraded = true,
                            isCloud = false,
                        ),
                    )
                    if (serveFull) {
                        delay(fullDelayMs)
                        emit(
                            LoadedImage(
                                ImageBitmap(width = 2, height = 2),
                                isDegraded = false,
                                isCloud = false,
                            ),
                        )
                    }
                }

            override fun prefetch(
                ids: List<String>,
                targetPx: IntSize,
            ) = Unit

            override fun cancelPrefetch(
                ids: List<String>,
                targetPx: IntSize,
            ) = Unit
        }

    @Test
    fun `NFR-1 a commit opens both next-card spans and the new card's images close them`() =
        runComposeUiTest {
            val trace = FakePerfTrace()
            val loader = steppedLoader()
            val state = MutableStateFlow(deckState())
            setContent {
                TraceScreen(state, trace, loader)
            }
            waitForIdle()
            // The initial card's images arrive outside any commit: nothing opens.
            assertEquals(emptyList(), trace.calls)

            onNodeWithContentDescription("Skip").performClick()
            mainClock.advanceTimeBy((NUDGE_MS + FLY_OFF_MS).toLong() + 50L)
            waitForIdle()
            assertEquals(
                listOf(
                    FakePerfTrace.Call.BEGIN to PerfSpan.NEXT_CARD_DEGRADED,
                    FakePerfTrace.Call.BEGIN to PerfSpan.NEXT_CARD_FULL,
                ),
                trace.calls,
            )

            // The ViewModel advanced the deck (NFR-7): the next card becomes
            // the top card and its degraded-then-full emissions close the
            // spans in order (NFR-1). The delay advances the test clock past
            // the full image's emission.
            state.value = SwipeUiState(current = card("next-id"), next = card("third-id"))
            mainClock.advanceTimeBy(300L)
            waitForIdle()
            assertEquals(
                listOf(
                    FakePerfTrace.Call.BEGIN to PerfSpan.NEXT_CARD_DEGRADED,
                    FakePerfTrace.Call.BEGIN to PerfSpan.NEXT_CARD_FULL,
                    FakePerfTrace.Call.END to PerfSpan.NEXT_CARD_DEGRADED,
                    FakePerfTrace.Call.END to PerfSpan.NEXT_CARD_FULL,
                ),
                trace.calls,
            )
        }

    @Test
    fun `NFR-1 a card that never serves the full image leaves the full span open`() =
        runComposeUiTest {
            val trace = FakePerfTrace()
            val loader = steppedLoader(serveFull = false)
            val state = MutableStateFlow(deckState())
            setContent {
                TraceScreen(state, trace, loader)
            }
            waitForIdle()
            onNodeWithContentDescription("Skip").performClick()
            mainClock.advanceTimeBy((NUDGE_MS + FLY_OFF_MS).toLong() + 50L)
            waitForIdle()
            state.value = SwipeUiState(current = card("next-id"), next = null)
            mainClock.advanceTimeBy(300L)
            waitForIdle()
            // Degraded arrived, full never did: the full span stays open and
            // simply shows no interval in the trace (docs/perf.md).
            assertEquals(
                listOf(
                    FakePerfTrace.Call.BEGIN to PerfSpan.NEXT_CARD_DEGRADED,
                    FakePerfTrace.Call.BEGIN to PerfSpan.NEXT_CARD_FULL,
                    FakePerfTrace.Call.END to PerfSpan.NEXT_CARD_DEGRADED,
                ),
                trace.calls,
            )
        }

    /** The screen driven by an externally advanced [state] with [trace] hooked in. */
    @Composable
    private fun TraceScreen(
        state: StateFlow<SwipeUiState>,
        trace: FakePerfTrace,
        mediaLoader: MediaLoader,
    ) {
        SwipeTheme {
            SwipeScreen(
                state = state.collectAsState().value,
                events = flowOf(),
                onIntent = {},
                mediaLoader = mediaLoader,
                haptics = FakeHaptics(),
                perfTrace = trace,
                reduceMotion = false,
                onClose = {},
                onOpenQueue = {},
            )
        }
    }
}
