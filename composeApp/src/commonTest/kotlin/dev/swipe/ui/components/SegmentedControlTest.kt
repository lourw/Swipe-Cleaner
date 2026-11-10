// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.theme.SwipeSizes
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M1 media-type filter control (§5.7): selection state and callbacks. */
class SegmentedControlTest {
    private val all = "All"
    private val photos = "Photos"
    private val videos = "Videos"

    @Test
    fun `5-7 segments expose role and selection state`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    SegmentedControl(
                        options = listOf(photos, videos),
                        selected = photos,
                        labelOf = { it },
                        onSelect = {},
                    )
                }
            }
            val selectedNode = onNodeWithText(photos).fetchSemanticsNode()
            val unselectedNode = onNodeWithText(videos).fetchSemanticsNode()
            assertEquals(Role.Tab, selectedNode.config[SemanticsProperties.Role])
            assertTrue(selectedNode.config[SemanticsProperties.Selected])
            assertFalse(unselectedNode.config[SemanticsProperties.Selected])
        }

    @Test
    fun `5-7 tapping a segment selects it and reports the value`() =
        runComposeUiTest {
            // Snapshot state so the recomposition after the tap refreshes the
            // `selected` semantics; a plain var would update the value but
            // never re-render.
            val selected = mutableStateOf(photos)
            setContent {
                SwipeTheme {
                    SegmentedControl(
                        options = listOf(photos, videos),
                        selected = selected.value,
                        labelOf = { it },
                        onSelect = { tapped -> selected.value = tapped },
                    )
                }
            }
            onNodeWithText(videos).performClick()
            waitForIdle()
            assertEquals(videos, selected.value)
            assertTrue(onNodeWithText(videos).fetchSemanticsNode().config[SemanticsProperties.Selected])
        }

    /**
     * FR-5 centering (WP-8.8): on a full-width track — as Home renders it —
     * the segments sit at the track's center, with the same empty space to
     * their left and right.
     */
    @Test
    fun `FR-5 segments are centered in the track with equal space on both sides`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    SegmentedControl(
                        options = listOf(all, photos, videos),
                        selected = all,
                        labelOf = { it },
                        onSelect = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val track =
                onNodeWithTag(SEGMENTED_CONTROL_TRACK_TEST_TAG)
                    .fetchSemanticsNode()
                    .boundsInWindow
            val segmentsLeft =
                onNodeWithText(all).fetchSemanticsNode().boundsInWindow.left
            val segmentsRight =
                onNodeWithText(videos).fetchSemanticsNode().boundsInWindow.right
            val spaceBefore = segmentsLeft - track.left
            val spaceAfter = track.right - segmentsRight
            assertTrue(
                spaceAfter > 0f,
                "the segments must not fill the track, or centering means nothing",
            )
            // 1.5 covers the px rounding of an odd leftover (Arrangement.Center
            // truncates); an off-center track is off by hundreds.
            assertEquals(spaceBefore, spaceAfter, 1.5f, "the segments sit at the track's center")
        }

    /** FR-5 (WP-8.8): centering keeps every segment's §5.5 44 dp touch target. */
    @Test
    fun `FR-5 segments keep the 44 dp touch target`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    SegmentedControl(
                        options = listOf(all, photos, videos),
                        selected = all,
                        labelOf = { it },
                        onSelect = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            for (label in listOf(all, photos, videos)) {
                onNodeWithText(label).assertHeightIsAtLeast(SwipeSizes().minTouchTarget)
            }
        }
}
