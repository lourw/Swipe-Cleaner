// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.swipe

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.domain.model.Progress
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NFR-8: the queue count is announced once. The pill beside the queue button
 * is decoration — the button's own label already carries the number — so
 * VoiceOver must not find a second "7 queued" node next to the button.
 */
class TopBarTest {
    @Test
    fun `NFR-8 the queue count is announced once not twice`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    TopBar(
                        progress = Progress(reviewed = 12, total = 40),
                        queueCount = 7,
                        onClose = {},
                        onOpenQueue = {},
                        onHide = {},
                    )
                }
            }
            // The button label carries the count…
            assertEquals(
                1,
                onAllNodes(hasContentDescription("7 queued — open the queue")).fetchSemanticsNodes().size,
            )
            // …and the badge adds no second announcement of it.
            assertEquals(
                0,
                onAllNodes(hasContentDescription("7 queued")).fetchSemanticsNodes().size,
            )
        }

    @Test
    fun `FR-10 an empty queue hides the badge and the count`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    TopBar(
                        progress = Progress(reviewed = 0, total = 40),
                        queueCount = 0,
                        onClose = {},
                        onOpenQueue = {},
                        onHide = {},
                    )
                }
            }
            assertEquals(0, onAllNodes(hasContentDescription("0 queued")).fetchSemanticsNodes().size)
            assertEquals(
                1,
                onAllNodes(hasContentDescription("To delete queue")).fetchSemanticsNodes().size,
            )
        }
}
