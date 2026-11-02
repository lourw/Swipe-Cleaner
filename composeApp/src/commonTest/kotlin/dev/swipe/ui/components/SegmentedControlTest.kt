// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M1 media-type filter control (§5.7): selection state and callbacks. */
class SegmentedControlTest {
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
}
