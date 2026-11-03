// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The §6.7 in-app confirm dialog (NFR-5 gate): title and message render, the
 * destructive confirm is the only path that proceeds, and Cancel — either
 * label — never confirms.
 */
class ConfirmDialogTest {
    @Test
    fun `NFR-5 the dialog shows its title and message`() =
        runComposeUiTest {
            setContent {
                TestDialog(onConfirm = {}, onCancel = {})
            }
            onNodeWithText("Delete 3 items?").assertExists()
            onNodeWithText("They'll move to Recently Deleted for 30 days.").assertExists()
        }

    @Test
    fun `NFR-5 only the confirm button confirms`() =
        runComposeUiTest {
            val calls = mutableListOf<String>()
            setContent {
                TestDialog(onConfirm = { calls += "confirm" }, onCancel = { calls += "cancel" })
            }
            // The confirm pill exposes its label as its content description
            // (PrimaryButton), so the click targets the clickable node itself.
            onNodeWithContentDescription("Delete 3 items").performClick()
            waitForIdle()
            assertEquals(listOf("confirm"), calls)
        }

    @Test
    fun `NFR-5 cancel cancels and never confirms`() =
        runComposeUiTest {
            val calls = mutableListOf<String>()
            setContent {
                TestDialog(onConfirm = { calls += "confirm" }, onCancel = { calls += "cancel" })
            }
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertEquals(listOf("cancel"), calls)
        }

    @Composable
    private fun TestDialog(
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        SwipeTheme {
            ConfirmDialog(
                title = "Delete 3 items?",
                message = "They'll move to Recently Deleted for 30 days.",
                confirmLabel = "Delete 3 items",
                onConfirm = onConfirm,
                onCancel = onCancel,
            )
        }
    }
}
