// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.onboarding

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** FR-3 denied screen: blocking explanation plus an Open Settings action. */
class DeniedScreenTest {
    @Test
    fun `FR-3 denied screen explains and opens settings`() =
        runComposeUiTest {
            var taps = 0
            setContent {
                SwipeTheme {
                    DeniedScreen(onOpenSettings = { taps++ })
                }
            }
            onNodeWithText("Photos access needed").assertExists()
            onNodeWithText(
                "Swipe Cleaner needs access to your photo library to sort through " +
                    "it. Access is off right now.",
            ).assertExists()
            onNodeWithText("Open Settings").performClick()
            assertEquals(1, taps)
        }
}
