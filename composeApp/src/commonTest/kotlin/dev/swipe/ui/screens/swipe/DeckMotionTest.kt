// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.components.swipe.SwipeCardState
import dev.swipe.ui.components.swipe.SwipeVerdict
import dev.swipe.ui.components.swipe.rememberSwipeCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow

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
}
