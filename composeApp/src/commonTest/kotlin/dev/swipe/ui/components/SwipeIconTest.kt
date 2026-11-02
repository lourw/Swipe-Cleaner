// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.resources.Res
import dev.swipe.resources.ic_check
import kotlin.test.Test
import kotlin.test.assertEquals

/** §5.3 icon semantics: given descriptions are announced; decorative ones hide. */
class SwipeIconTest {
    @Test
    fun `5-3 SwipeIcon exposes the content description it is given`() =
        runComposeUiTest {
            setContent { SwipeIcon(Res.drawable.ic_check, contentDescription = "Keep") }
            val node = onNodeWithContentDescription("Keep").fetchSemanticsNode()
            assertEquals(listOf("Keep"), node.config[SemanticsProperties.ContentDescription])
        }

    @Test
    fun `5-3 decorative SwipeIcon is hidden from accessibility`() =
        runComposeUiTest {
            setContent { SwipeIcon(Res.drawable.ic_check, contentDescription = null) }
            // A null description must publish no ContentDescription semantics
            // at all (Image hides itself), unlike the old empty-string node.
            onNodeWithContentDescription("").assertDoesNotExist()
        }
}
