// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.presentation.permission.PermissionIntent
import dev.swipe.presentation.permission.PermissionScreen
import dev.swipe.presentation.permission.PermissionUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP-1.7 routing: [PermissionFlowContent] starts on the screen the
 * [PermissionUiState] names, forwards button taps as intents, and follows the
 * state when it routes onward (FR-1–3).
 */
class PermissionFlowContentTest {
    @Test
    fun `FR-1 flow waits for the first authorization read`() =
        runComposeUiTest {
            setContent { PermissionFlowContent(state = PermissionUiState(), onIntent = {}) }
            onNodeWithText("Allow access").assertDoesNotExist()
            onNodeWithText("Start shuffle").assertDoesNotExist()
        }

    @Test
    fun `FR-1 flow starts on onboarding and allow access sends RequestAccess`() =
        runComposeUiTest {
            val intents = mutableListOf<PermissionIntent>()
            setContent {
                PermissionFlowContent(
                    state = PermissionUiState(startScreen = PermissionScreen.ONBOARDING),
                    onIntent = { intents.add(it) },
                )
            }
            onNodeWithText("Allow access").performClick()
            assertEquals(listOf<PermissionIntent>(PermissionIntent.RequestAccess), intents)
        }

    @Test
    fun `FR-3 flow routes denied to the blocking screen and sends OpenSettings`() =
        runComposeUiTest {
            val intents = mutableListOf<PermissionIntent>()
            setContent {
                PermissionFlowContent(
                    state = PermissionUiState(startScreen = PermissionScreen.DENIED),
                    onIntent = { intents.add(it) },
                )
            }
            onNodeWithText("Open Settings").performClick()
            assertEquals(listOf<PermissionIntent>(PermissionIntent.OpenSettings), intents)
        }

    @Test
    fun `FR-2 flow shows home with the limited banner and sends ManageLimitedAccess`() =
        runComposeUiTest {
            val intents = mutableListOf<PermissionIntent>()
            setContent {
                PermissionFlowContent(
                    state =
                        PermissionUiState(
                            startScreen = PermissionScreen.HOME,
                            access = LibraryAccess.LIMITED,
                            limitedCount = 7,
                        ),
                    onIntent = { intents.add(it) },
                )
            }
            onNodeWithText("You've given access to 7 items.").assertExists()
            onNodeWithText("Manage").performClick()
            assertEquals(listOf<PermissionIntent>(PermissionIntent.ManageLimitedAccess), intents)
        }

    @Test
    fun `FR-1 flow follows the state from onboarding to home after access is granted`() =
        runComposeUiTest {
            val state: MutableState<PermissionUiState> =
                mutableStateOf(PermissionUiState(startScreen = PermissionScreen.ONBOARDING))
            setContent { PermissionFlowContent(state = state.value, onIntent = {}) }
            onNodeWithText("Allow access").assertExists()

            state.value =
                PermissionUiState(
                    startScreen = PermissionScreen.HOME,
                    access = LibraryAccess.FULL,
                )
            waitForIdle()
            onNodeWithText("Start shuffle").assertExists()
            onNodeWithText("Allow access").assertDoesNotExist()
        }
}
