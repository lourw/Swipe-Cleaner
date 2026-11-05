// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components.swipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The §5.6 gesture end to end on the real [SwipeCard]: drags past each
 * direction's threshold commit on release with no fling (velocity pinned to
 * zero by [swipeWithVelocity]), a short drag snaps back, the button path
 * (`flyOff(verdict)`) nudges then commits, Reduce Motion commits through
 * the 150 ms crossfade without ever rotating — read through the card's
 * [RotationZSemanticsPropertyKey] hook, since graphicsLayer transforms are
 * invisible to semantics otherwise — and the verdict stamp rides the drag,
 * found through the tag [stampTestTag] leaves on its cleared node.
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

    /** The rotation the card layer actually applies, via the test hook. */
    private fun SemanticsNodeInteractionsProvider.appliedRotationZ(): Float =
        onNodeWithTag(CARD_TAG).fetchSemanticsNode().config[RotationZSemanticsPropertyKey]

    @Test
    fun `5-6 drag past threshold commits KEEP`() =
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
    fun `5-6 drag left past threshold commits DELETE`() =
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
    fun `5-6 drag up past threshold commits SKIP`() =
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
    fun `5-6 short drag snaps back without commit`() =
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
    fun `5-6 button fly-off nudges then commits`() =
        runComposeUiTest {
            var cardState: SwipeCardState? = null
            var commitViaButton: ((SwipeVerdict) -> Unit)? = null
            // Drive frames by hand to check the nudge and the flight apart.
            mainClock.autoAdvance = false
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    val state = rememberSwipeCardState()
                    val scope = rememberCoroutineScope()
                    cardState = state
                    commitViaButton = { verdict -> scope.launch { state.flyOff(verdict) } }
                    SwipeCard(
                        onDismiss = {},
                        state = state,
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        content = {},
                    )
                }
            }
            val width = onNodeWithTag(CARD_TAG).fetchSemanticsNode().size.width
            commitViaButton!!(SwipeVerdict.KEEP)
            // Mid-nudge (§5.6: 30 dp over 80 ms): moving right, still on screen.
            mainClock.advanceTimeBy(NUDGE_MS / 2L)
            assertTrue((cardState?.offset?.x ?: 0f) > 0f)
            assertTrue((cardState?.offset?.x ?: Float.MAX_VALUE) < width)
            // Then the normal fly-off clears the screen; the verdict is committed.
            mainClock.advanceTimeBy((NUDGE_MS + FLY_OFF_MS).toLong() + 50L)
            assertEquals(SwipeVerdict.KEEP, cardState?.committedVerdict)
            assertTrue((cardState?.offset?.x ?: 0f) >= width)
        }

    @Test
    fun `5-6 card rotates with the drag when motion is on`() =
        runComposeUiTest {
            var dismissed: SwipeVerdict? = null
            mainClock.autoAdvance = false
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwipeCard(
                        onDismiss = { dismissed = it },
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        content = {},
                    )
                }
            }
            val size = onNodeWithTag(CARD_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            // Half a card width right: the §5.6 ramp is (dx / cardWidth) × 12°
            // — minus the touch slop the recognizer consumes before the drag
            // starts, hence the loose tolerance. This also proves the hook
            // publishes real rotation, so the zero asserts below are honest.
            onNodeWithTag(CARD_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width / 2f, start.y))
            }
            mainClock.advanceTimeBy(32L)
            assertEquals(MAX_ROTATION_DEG / 2f, appliedRotationZ(), 1f)
            onNodeWithTag(CARD_TAG).performTouchInput { up() }
            mainClock.advanceTimeBy(FLY_OFF_MS.toLong() + 50L)
            assertEquals(SwipeVerdict.KEEP, dismissed)
        }

    @Test
    fun `5-6 reduce motion commits without rotation`() =
        runComposeUiTest {
            var dismissed: SwipeVerdict? = null
            // Drive frames by hand: waitForIdle() with auto-advance would run
            // the whole crossfade before the mid-animation assertions below.
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
            val size = onNodeWithTag(CARD_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            // Drag past the 35 % threshold and pause mid-drag: §5.6 Reduce
            // Motion never rotates, so the applied rotation is 0 even while
            // the card follows the finger sideways.
            onNodeWithTag(CARD_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * (COMMIT_FRACTION_HORIZONTAL + 0.1f), start.y))
            }
            mainClock.advanceTimeBy(32L)
            assertEquals(0f, appliedRotationZ())
            onNodeWithTag(CARD_TAG).performTouchInput { up() }
            // Mid-crossfade: still square, and the commit has not landed.
            mainClock.advanceTimeBy(REDUCE_MOTION_CROSSFADE_MS / 2L)
            assertNull(dismissed)
            assertEquals(0f, appliedRotationZ())
            // The crossfade finishes at 150 ms — well before the 220 ms
            // flight a motion-enabled commit would take — and the card never
            // rotated on the way out either.
            mainClock.advanceTimeBy(REDUCE_MOTION_CROSSFADE_MS / 2L + 50L)
            assertEquals(SwipeVerdict.KEEP, dismissed)
            assertEquals(0f, appliedRotationZ())
        }

    @Test
    fun `5-6 stamp appears when drag crosses into a verdict`() =
        runComposeUiTest {
            // Drive frames by hand so the mid-drag assert lands before release.
            mainClock.autoAdvance = false
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwipeCard(
                        onDismiss = {},
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        content = {},
                    )
                }
            }
            val size = onNodeWithTag(CARD_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            // Half way to the 35 % threshold: stampAlpha ramps linearly from
            // zero, so the KEEP stamp is mounted and fading in mid-drag.
            onNodeWithTag(CARD_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * COMMIT_FRACTION_HORIZONTAL / 2f, start.y))
            }
            mainClock.advanceTimeBy(32L)
            // The stamp's cleared semantics node rides the unmerged tree.
            onNodeWithTag(stampTestTag(SwipeVerdict.KEEP), useUnmergedTree = true).assertExists()
            // Below-threshold release springs the card back; the stamp's
            // alpha reaches zero and it unmounts with it.
            onNodeWithTag(CARD_TAG).performTouchInput { up() }
            mainClock.advanceTimeBy(1000L)
            waitForIdle()
            onNodeWithTag(stampTestTag(SwipeVerdict.KEEP), useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun `NFR-8 swipe card exposes keep delete skip accessibility actions`() =
        runComposeUiTest {
            val fired = mutableListOf<String>()
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwipeCard(
                        onDismiss = {},
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        accessibilityDescription = "Mar 14, 2021, photo",
                        accessibilityActions =
                            listOf(
                                CardAccessibilityAction("Keep") { fired += "Keep" },
                                CardAccessibilityAction("Delete") { fired += "Delete" },
                                CardAccessibilityAction("Skip") { fired += "Skip" },
                            ),
                        content = {},
                    )
                }
            }
            // The card is one VoiceOver element — its date-then-type line —
            // carrying one custom action per decision, so no gesture is ever
            // required (NFR-8).
            val node = onNodeWithContentDescription("Mar 14, 2021, photo").fetchSemanticsNode()
            val actions = node.config[SemanticsActions.CustomActions]
            assertEquals(listOf("Keep", "Delete", "Skip"), actions.map { it.label })
            // Firing an action runs exactly its callback — the caller wires
            // these to the same paths the ActionBar buttons take.
            actions.first { it.label == "Delete" }.action()
            assertEquals(listOf("Delete"), fired)
        }

    @Test
    fun `5-6 reduce motion disables rotation and fly-off`() =
        runComposeUiTest {
            var cardState: SwipeCardState? = null
            var dismissed: SwipeVerdict? = null
            mainClock.autoAdvance = false
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    val state = rememberSwipeCardState()
                    cardState = state
                    SwipeCard(
                        onDismiss = { dismissed = it },
                        state = state,
                        reduceMotion = true,
                        modifier = Modifier.fillMaxSize().testTag(CARD_TAG),
                        content = {},
                    )
                }
            }
            val size = onNodeWithTag(CARD_TAG).fetchSemanticsNode().size
            val start = Offset(size.width / 2f, size.height / 2f)
            // Drag past the threshold: the card follows the finger (that is
            // not motion) but never rotates (§5.6 Reduce Motion).
            onNodeWithTag(CARD_TAG).performTouchInput {
                down(start)
                moveTo(Offset(start.x + size.width * (COMMIT_FRACTION_HORIZONTAL + 0.1f), start.y))
            }
            mainClock.advanceTimeBy(32L)
            assertEquals(0f, appliedRotationZ())
            val releaseOffset = checkNotNull(cardState).offset
            assertTrue(releaseOffset.x > 0f)
            onNodeWithTag(CARD_TAG).performTouchInput { up() }
            // Mid-crossfade: the card fades out in place — no fly-off, so
            // the offset has not moved a pixel since release — while the
            // alpha is on its way down and the rotation is still zero.
            mainClock.advanceTimeBy(REDUCE_MOTION_CROSSFADE_MS / 2L)
            assertEquals(releaseOffset, checkNotNull(cardState).offset)
            val midAlpha = checkNotNull(cardState).alpha
            assertTrue(midAlpha < 1f)
            assertEquals(0f, appliedRotationZ())
            // The commit lands at the end of the 150 ms crossfade, still
            // fully faded and still in place.
            mainClock.advanceTimeBy(REDUCE_MOTION_CROSSFADE_MS / 2L + 50L)
            assertEquals(SwipeVerdict.KEEP, dismissed)
            assertEquals(releaseOffset, checkNotNull(cardState).offset)
            assertEquals(0f, checkNotNull(cardState).alpha)
        }

    private companion object {
        const val CARD_TAG = "swipe-card"
    }
}
