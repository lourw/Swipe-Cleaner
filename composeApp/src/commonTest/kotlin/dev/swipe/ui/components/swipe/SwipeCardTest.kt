// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components.swipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The §5.6 gesture end to end on the real [SwipeCard]: drags past each
 * direction's threshold commit on release with no fling (velocity pinned to
 * zero by [swipeWithVelocity]), a short drag snaps back, and Reduce Motion
 * commits through the 150 ms crossfade instead of the 220 ms flight.
 */
class SwipeCardTest {
    /** Drags the tagged card by fractions of its own size and releases at rest. */
    private fun SemanticsNodeInteractionsProvider.swipeCard(
        xFraction: Float,
        yFraction: Float,
    ) {
        val node = onNodeWithTag(CARD_TAG).fetchSemanticsNode()
        val size = node.size
        val start = Offset(size.width / 2f, size.height / 2f)
        onNodeWithTag(CARD_TAG).performTouchInput {
            swipeWithVelocity(
                start = start,
                end =
                    Offset(
                        x = start.x + size.width * xFraction,
                        y = start.y + size.height * yFraction,
                    ),
                // End at rest so only the offset thresholds can commit.
                endVelocity = 0f,
            )
        }
    }

    @Test
    fun `5-6 drag right past threshold commits keep`() =
        runComposeUiTest {
            var dismissed: SwipeVerdict? = null
            var crossings = 0
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwipeCard(
                        onDismiss = { dismissed = it },
                        onThresholdCrossed = { crossings += 1 },
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        content = {},
                    )
                }
            }
            swipeCard(xFraction = COMMIT_FRACTION_HORIZONTAL + 0.1f, yFraction = 0f)
            waitForIdle()
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong())
            waitForIdle()
            assertEquals(SwipeVerdict.KEEP, dismissed)
            // Crossed once on the way out, and not again while flying off.
            assertEquals(1, crossings)
        }

    @Test
    fun `5-6 drag left commits delete`() =
        runComposeUiTest {
            var dismissed: SwipeVerdict? = null
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwipeCard(
                        onDismiss = { dismissed = it },
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        content = {},
                    )
                }
            }
            swipeCard(xFraction = -(COMMIT_FRACTION_HORIZONTAL + 0.1f), yFraction = 0f)
            waitForIdle()
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong())
            waitForIdle()
            assertEquals(SwipeVerdict.DELETE, dismissed)
        }

    @Test
    fun `5-6 drag up commits skip`() =
        runComposeUiTest {
            var dismissed: SwipeVerdict? = null
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwipeCard(
                        onDismiss = { dismissed = it },
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        content = {},
                    )
                }
            }
            swipeCard(xFraction = 0f, yFraction = -(COMMIT_FRACTION_UP + 0.1f))
            waitForIdle()
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong())
            waitForIdle()
            assertEquals(SwipeVerdict.SKIP, dismissed)
        }

    @Test
    fun `5-6 short drag snaps back without committing`() =
        runComposeUiTest {
            var dismissed: SwipeVerdict? = null
            var crossings = 0
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwipeCard(
                        onDismiss = { dismissed = it },
                        onThresholdCrossed = { crossings += 1 },
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        content = {},
                    )
                }
            }
            swipeCard(xFraction = COMMIT_FRACTION_HORIZONTAL / 2f, yFraction = 0f)
            waitForIdle()
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            assertNull(dismissed)
            // Never crossed the 35 % threshold, so no haptic either.
            assertEquals(0, crossings)
        }

    @Test
    fun `5-6 reduce motion commits with a 150 ms crossfade`() =
        runComposeUiTest {
            var dismissed: SwipeVerdict? = null
            // Drive frames by hand: waitForIdle() with auto-advance would run
            // the whole crossfade before the mid-animation assertion below.
            mainClock.autoAdvance = false
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwipeCard(
                        onDismiss = { dismissed = it },
                        reduceMotion = true,
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        content = {},
                    )
                }
            }
            swipeCard(xFraction = COMMIT_FRACTION_HORIZONTAL + 0.1f, yFraction = 0f)
            // Process the release and the first frames of the crossfade.
            mainClock.advanceTimeBy(REDUCE_MOTION_CROSSFADE_MS / 2L)
            assertNull(dismissed)
            // The crossfade finishes at 150 ms — well before the 220 ms
            // flight a motion-enabled commit would take.
            mainClock.advanceTimeBy(REDUCE_MOTION_CROSSFADE_MS.toLong() + 50L)
            assertEquals(SwipeVerdict.KEEP, dismissed)
        }

    private companion object {
        const val CARD_TAG = "swipe-card"
    }
}
