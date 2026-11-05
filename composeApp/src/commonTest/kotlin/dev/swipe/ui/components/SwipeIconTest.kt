// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * §5.3 icon semantics: given descriptions are announced; decorative ones hide.
 * SwipeIcon renders a font glyph through `dev.vicart:compose-material-symbols`,
 * whose `BasicText` would expose the symbol *name* as text semantics — these
 * tests pin that the name never reaches the accessibility tree.
 */
class SwipeIconTest {
    @Test
    fun `5-3 SwipeIcon exposes the content description it is given`() =
        runComposeUiTest {
            setContent { SwipeIcon(SwipeIcons.CHECK, contentDescription = "Keep") }
            val node = onNodeWithContentDescription("Keep").fetchSemanticsNode()
            assertEquals(listOf("Keep"), node.config[SemanticsProperties.ContentDescription])
            // The glyph is drawn from the symbol name; the raw name must not leak as text.
            assertFalse(node.config.contains(SemanticsProperties.Text))
        }

    @Test
    fun `5-3 decorative SwipeIcon is hidden from accessibility`() =
        runComposeUiTest {
            setContent {
                SwipeIcon(SwipeIcons.CHECK, modifier = Modifier.testTag("icon"), contentDescription = null)
            }
            // A null description must publish no ContentDescription semantics
            // at all, and no glyph text either (the node's config is empty).
            val node = onNodeWithTag("icon").fetchSemanticsNode()
            assertFalse(node.config.contains(SemanticsProperties.ContentDescription))
            assertFalse(node.config.contains(SemanticsProperties.Text))
        }

    @Test
    fun `5-3 SwipeIcon leaks no glyph name into the semantics tree`() =
        runComposeUiTest {
            setContent {
                SwipeIcon(SwipeIcons.CHECK, contentDescription = "Keep")
                SwipeIcon(SwipeIcons.CLOSE, contentDescription = null)
            }
            assertEquals(emptyList(), onAllNodes(hasText(SwipeIcons.CHECK)).fetchSemanticsNodes())
            assertEquals(emptyList(), onAllNodes(hasText(SwipeIcons.CLOSE)).fetchSemanticsNodes())
        }

    @Test
    fun `5-3 CircleIconButton label is not polluted by the glyph name`() =
        runComposeUiTest {
            setContent { CircleIconButton(SwipeIcons.CLOSE, label = "Close", onClick = {}) }
            val node = onNodeWithContentDescription("Close").fetchSemanticsNode()
            // VoiceOver reads the merged node: the label alone, never "close, Close".
            assertFalse(node.config.contains(SemanticsProperties.Text))
            onNodeWithText(SwipeIcons.CLOSE).assertDoesNotExist()
        }
}
