// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.data.LoadedImage
import dev.swipe.ui.components.media.promotedImage
import dev.swipe.ui.components.swipe.SwipeCardState
import dev.swipe.ui.components.swipe.SwipeVerdict
import dev.swipe.ui.components.swipe.rememberSwipeCardState
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NFR-7: the failed-write revert animates on the bump that caused it and on
 * nothing else. [rememberRevertGuard] carries the consumed token across
 * disposal and restoration, so re-entering the deck never replays the flight.
 */
class DeckMotionTest {
    @Test
    fun `NFR-7 the guard fires once per token bump and never on 0`() =
        runComposeUiTest {
            val registry = SaveableStateRegistry(null, { true })
            lateinit var guard: (Long) -> Boolean
            setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    guard = rememberRevertGuard()
                }
            }
            assertFalse(guard(0L), "the initial token is not a revert")
            assertTrue(guard(1L), "the first bump reverts")
            assertFalse(guard(1L), "the same token never reverts twice")
            assertTrue(guard(2L), "a newer bump reverts again")
        }

    @Test
    fun `NFR-7 a restored guard ignores the token it already consumed`() =
        runComposeUiTest {
            // One content lambda composed twice: the positional save keys of
            // the two passes agree, exactly as they do across a real dispose
            // and restore of the screen.
            lateinit var guard: (Long) -> Boolean
            var registry = SaveableStateRegistry(null, { true })
            val content =
                @Composable {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        guard = rememberRevertGuard()
                    }
                }
            setContent(content)
            assertTrue(guard(1L))
            awaitIdle()
            val saved = registry.performSave()
            // Re-entry: a fresh composition over the saved state, the same
            // token — the flight must not replay.
            registry = SaveableStateRegistry(saved, { true })
            setContent(content)
            assertFalse(guard(1L), "an already-consumed token must not replay after restoration")
            assertTrue(guard(2L), "a bump after the restoration still reverts")
        }

    @Test
    fun `NFR-7 a token bump flies the committed card back to rest`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            var token by mutableLongStateOf(0L)
            lateinit var card: SwipeCardState
            setContent {
                card = rememberSwipeCardState()
                card.cardSize = Size(300f, 400f)
                CardMotionEffects(card, emptyFlow(), token, currentId = "card-a", reduceMotion = false)
            }
            mainClock.advanceTimeBy(16L)
            // The at-rest reset owns the first frame: no token, no flight.
            assertEquals(Offset.Zero, card.offset)
            // The fly-off that just failed, and the bump that reports it.
            card.committedVerdict = SwipeVerdict.DELETE
            token = 1L
            mainClock.advanceTimeBy(16L)
            // Mid-flight: the card is back at the delete edge, heading in.
            assertTrue(card.offset.x < 0f, "expected a flight in from the delete edge, offset=${card.offset}")
            mainClock.advanceTimeBy(1000L)
            awaitIdle()
            assertEquals(Offset.Zero, card.offset, "the card lands back at rest")
            assertEquals(null, card.committedVerdict, "the returned card is back in the deck")
        }

    /**
     * The whole wiring, not just the guard: navigating away from the deck
     * disposes `CardMotionEffects` and back restores it over the saved state,
     * with the consumed token still in hand. A recomposition of a live card
     * cannot expose a replay — [SwipeCardState.resetForNext] would snap the
     * card back mid-flight — so this drives the shape the guard exists for:
     * the token rides saveable state (the high-water mark is
     * `rememberSaveable`, not `remember`) through a save, is restored over a
     * fresh registry (the `performSave` result, as navigation hands it back),
     * and re-arrives as a fresh effect key once the restored card is at rest.
     * An unguarded `revertToken > 0` check flies the card in on that
     * re-arrival; the saved high-water mark must swallow it.
     */
    @Test
    fun `NFR-7 a restored CardMotionEffects does not replay the consumed revert`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            var token by mutableLongStateOf(0L)
            var visible by mutableStateOf(true)
            var registry by mutableStateOf(SaveableStateRegistry(null, { true }))
            lateinit var card: SwipeCardState
            setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    card = rememberSwipeCardState()
                    card.cardSize = Size(300f, 400f)
                    if (visible) {
                        CardMotionEffects(card, emptyFlow(), token, currentId = "card-a", reduceMotion = false)
                    }
                }
            }
            mainClock.advanceTimeBy(16L)
            // The fly-off that just failed, and the bump that reports it.
            card.committedVerdict = SwipeVerdict.DELETE
            token = 1L
            mainClock.advanceTimeBy(16L)
            assertTrue(card.offset.x < 0f, "the bump flies the card back, offset=${card.offset}")
            mainClock.advanceTimeBy(1000L)
            awaitIdle()
            assertEquals(Offset.Zero, card.offset, "the returned card rests")
            // Away: the save carries the consumed token, and the effects are
            // disposed with it still in hand.
            val saved = registry.performSave()
            check(saved.isNotEmpty()) { "the save carried the guard state: $saved" }
            visible = false
            mainClock.advanceTimeBy(16L)
            // Back: a fresh registry over the saved state restores the guard,
            // and the spent value re-arrives as a fresh key once the
            // restored card is at rest...
            token = 0L
            registry = SaveableStateRegistry(saved, { true })
            visible = true
            mainClock.advanceTimeBy(16L)
            token = 1L
            // ...and the guard must swallow it: no flight.
            mainClock.advanceTimeBy(16L)
            assertEquals(Offset.Zero, card.offset, "an already-consumed token must not fly the card in again")
            mainClock.advanceTimeBy(1000L)
            awaitIdle()
            assertEquals(Offset.Zero, card.offset, "and the card stays at rest")
        }

    /**
     * FR-38 (§5.6 Hide advance): the pure borrow rule the promoted top card uses.
     * The card's own load always wins; otherwise it borrows the under card's image
     * only when that image was loaded for the very same id — an image is drawn only
     * for the card it was loaded for (AC3).
     */
    @Test
    fun `FR-38 a promoted card borrows the under card image only for its own id`() {
        val own = LoadedImage(ImageBitmap(1, 1), isDegraded = false, isCloud = false)
        val under = LoadedImage(ImageBitmap(2, 2), isDegraded = false, isCloud = false)
        // The card's own emission wins even when a borrow is available.
        assertEquals(own, promotedImage(cardId = "a", own = own, underId = "a", underImage = under))
        // No own image yet, same id: borrow the under card's already-loaded bitmap.
        assertEquals(under, promotedImage(cardId = "b", own = null, underId = "b", underImage = under))
        // A borrow for a different id must never draw that image.
        assertNull(promotedImage(cardId = "b", own = null, underId = "c", underImage = under))
        // Nothing loaded anywhere.
        assertNull(promotedImage(cardId = "b", own = null, underId = null, underImage = null))
    }
}
