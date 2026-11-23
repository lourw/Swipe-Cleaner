// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP-9.3's one toast host (§5.7/§6.6): it renders the spec, owns the 3 s
 * auto-dismiss and reports it to exactly one owner, keeps a confirm-style
 * spec up past the fuse, and routes the action tap. All delays run on the
 * test scheduler, so `mainClock.advanceTimeBy` drives them virtually.
 */
class ToastHostTest {
    /** The host under the theme, backed by one nullable spec the test edits. */
    @Composable
    private fun HostScreen(
        initial: ToastSpec?,
        onDismiss: () -> Unit = {},
        onAction: () -> Unit = {},
    ) {
        var toast by remember { mutableStateOf(initial) }
        SwipeTheme {
            Box {
                ToastHost(
                    toast = toast,
                    onDismiss = onDismiss,
                    onAction = onAction,
                )
            }
        }
    }

    @Test
    fun `5-7 host renders the spec text with assertive live region`() =
        runComposeUiTest {
            setContent { HostScreen(ToastSpec("Undone")) }
            onNodeWithText("Undone").assertExists()
            val node = onNodeWithContentDescription("Undone").fetchSemanticsNode()
            assertEquals(LiveRegionMode.Assertive, node.config[SemanticsProperties.LiveRegion])
        }

    @Test
    fun `5-7 host auto-dismisses after 3 s and tells its owner`() =
        runComposeUiTest {
            var dismissals = 0
            setContent { HostScreen(ToastSpec("Undone"), onDismiss = { dismissals += 1 }) }
            onNodeWithText("Undone").fetchSemanticsNode()
            mainClock.advanceTimeBy(3000)
            waitForIdle()
            assertEquals(1, dismissals)
        }

    @Test
    fun `5-7 host is still visible 1 second after it appears`() =
        runComposeUiTest {
            setContent { HostScreen(ToastSpec("Undone")) }
            onNodeWithText("Undone").fetchSemanticsNode()
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            onAllNodesWithText("Undone").assertCountEquals(1)
        }

    @Test
    fun `5-7 confirm-style toast stays up past the auto-dismiss window`() =
        runComposeUiTest {
            var dismissals = 0
            setContent {
                HostScreen(
                    ToastSpec("This is a favorite — delete anyway?", actionLabel = "Delete", autoDismisses = false),
                    onDismiss = { dismissals += 1 },
                )
            }
            onNodeWithText("This is a favorite — delete anyway?").assertExists()
            mainClock.advanceTimeBy(30_000)
            waitForIdle()
            onAllNodesWithText("This is a favorite — delete anyway?").assertCountEquals(1)
            assertEquals(0, dismissals)
        }

    @Test
    fun `5-7 host routes the action tap to its owner`() =
        runComposeUiTest {
            var taps = 0
            setContent {
                HostScreen(
                    ToastSpec("This is a favorite — delete anyway?", actionLabel = "Delete", autoDismisses = false),
                    onAction = { taps += 1 },
                )
            }
            onNodeWithContentDescription("This is a favorite — delete anyway?")
                .onChildren()
                .filter(hasContentDescription("Delete"))
                .onFirst()
                .performClick()
            waitForIdle()
            assertEquals(1, taps)
        }
}
