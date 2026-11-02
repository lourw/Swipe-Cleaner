package dev.swipe.ui.components

import androidx.compose.ui.semantics.Disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §5.7 PrimaryButton: renders the label, fires taps only when enabled, and
 * exposes button role and content-description semantics.
 */
class PrimaryButtonTest {
    @Test
    fun `§5.7 pressing the primary button fires its action`() =
        runComposeUiTest {
            var clicks = 0
            setContent { PrimaryButton(label = "Review", onClick = { clicks++ }) }
            onNodeWithText("Review").performClick()
            assertEquals(1, clicks)
        }

    @Test
    fun `§5.7 disabled primary button ignores taps and marks Disabled`() =
        runComposeUiTest {
            var clicks = 0
            setContent { PrimaryButton(label = "Review", enabled = false, onClick = { clicks++ }) }
            val node = onNodeWithText("Review").performClick().fetchSemanticsNode()
            assertEquals(0, clicks)
            assertTrue(node.config.contains(Disabled))
        }

    @Test
    fun `§5.7 primary button exposes button role and description`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    PrimaryButton(label = "Review", contentDescription = "Review queue", onClick = {})
                }
            }
            val node = onNodeWithText("Review").fetchSemanticsNode()
            assertEquals(Role.Button, node.config[SemanticsProperties.Role])
            assertEquals(listOf("Review queue"), node.config[SemanticsProperties.ContentDescription])
        }
}
