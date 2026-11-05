// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe.ui.screens.onboarding

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.ui.assertAllClickablesLabeled
import dev.swipe.ui.theme.SwipeTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** FR-1 onboarding: explains the swipe verbs and the batch-delete safety, then offers access. */
class OnboardingScreenTest {
    @Test
    fun `FR-1 onboarding explains swiping and offers allow access`() =
        runComposeUiTest {
            var taps = 0
            setContent {
                SwipeTheme {
                    OnboardingScreen(onAllowAccess = { taps++ })
                }
            }
            onNodeWithText("Swipe Cleaner").assertExists()
            onNodeWithText(
                "Go through your library one item at a time. Swipe right to keep, " +
                    "left to delete, up to skip.",
            ).assertExists()
            onNodeWithText(
                "Nothing is deleted as you swipe. Items you mark are collected " +
                    "into a queue you review and delete in one batch.",
            ).assertExists()
            onNodeWithText("Allow access").performClick()
            assertEquals(1, taps)
        }

    @Test
    fun `NFR-8 every screen's clickable nodes have content descriptions`() =
        runComposeUiTest {
            setContent {
                SwipeTheme {
                    OnboardingScreen(onAllowAccess = {})
                }
            }
            waitForIdle()
            assertAllClickablesLabeled()
        }
}
