// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)

package dev.swipe

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.permission.PermissionIntent
import dev.swipe.presentation.permission.PermissionScreen
import dev.swipe.presentation.permission.PermissionUiState
import dev.swipe.ui.navigation.DeleteSuccess
import dev.swipe.ui.navigation.Summary
import dev.swipe.ui.navigation.Swipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * WP-1.7 routing: [PermissionFlowContent] starts on the screen the
 * [PermissionUiState] names, forwards button taps as intents, and follows the
 * state when it routes onward (FR-1–3).
 */
class PermissionFlowContentTest {
    @Test
    fun `FR-1 flow waits for the first authorization read`() =
        runComposeUiTest {
            setContent {
                PermissionFlowContent(state = MutableStateFlow(PermissionUiState()), onIntent = {})
            }
            onNodeWithText("Allow access").assertDoesNotExist()
            onNodeWithText("Start shuffle").assertDoesNotExist()
        }

    @Test
    fun `FR-1 flow starts on onboarding and allow access sends RequestAccess`() =
        runComposeUiTest {
            val intents = mutableListOf<PermissionIntent>()
            setContent {
                PermissionFlowContent(
                    state = MutableStateFlow(PermissionUiState(startScreen = PermissionScreen.ONBOARDING)),
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
                    state = MutableStateFlow(PermissionUiState(startScreen = PermissionScreen.DENIED)),
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
                        MutableStateFlow(
                            PermissionUiState(
                                startScreen = PermissionScreen.HOME,
                                access = LibraryAccess.LIMITED,
                                limitedCount = 7,
                            ),
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
            val state = MutableStateFlow(PermissionUiState(startScreen = PermissionScreen.ONBOARDING))
            setContent { PermissionFlowContent(state = state, onIntent = {}) }
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

    /**
     * The routing pops Onboarding inclusively, so Home is the only entry left
     * on the stack: `popBackStack()` — exactly what the system back gesture
     * dispatches to — is a no-op and Onboarding cannot be returned to.
     */
    @Test
    fun `FR-1 back cannot return to onboarding once home is reached`() =
        runComposeUiTest {
            lateinit var controller: NavHostController
            val state = MutableStateFlow(PermissionUiState(startScreen = PermissionScreen.ONBOARDING))
            setContent {
                controller = rememberNavController()
                PermissionFlowContent(state = state, onIntent = {}, navController = controller)
            }
            onNodeWithText("Allow access").assertExists()

            state.value =
                PermissionUiState(
                    startScreen = PermissionScreen.HOME,
                    access = LibraryAccess.FULL,
                )
            waitForIdle()
            onNodeWithText("Start shuffle").assertExists()

            // System back lands here as popBackStack(); the last entry cannot
            // pop, so Home stays shown and no onboarding is left behind it.
            assertFalse(controller.popBackStack())
            onNodeWithText("Start shuffle").assertExists()
            onNodeWithText("Allow access").assertDoesNotExist()
        }

    /**
     * WP-1.9: Home's "Shuffle everything" navigates to the Swipe route with
     * deck source `All` (serialized per the route contract) and the ALL type
     * filter. The destination is stubbed so the deck pipeline (Koin) stays out
     * of the navigation test.
     */
    @Test
    fun `FR-4 home shuffle navigates to the swipe deck with the All source`() =
        runComposeUiTest {
            var routed: Swipe? = null
            setContent {
                PermissionFlowContent(
                    state =
                        MutableStateFlow(
                            PermissionUiState(
                                startScreen = PermissionScreen.HOME,
                                access = LibraryAccess.FULL,
                            ),
                        ),
                    onIntent = {},
                    swipeDestination = { route ->
                        routed = route
                        BasicText("deck-destination")
                    },
                )
            }
            onNodeWithText("Start shuffle").performClick()
            waitForIdle()

            onNodeWithText("deck-destination").assertExists()
            val route = requireNotNull(routed)
            assertEquals(DeckSource.All, Json.decodeFromString(DeckSource.serializer(), route.sourceJson))
            assertEquals(MediaTypeFilter.ALL.name, route.typeFilter)
        }

    /**
     * WP-3.3: the summary's "Review N to delete" CTA navigates to the review
     * queue (FR-23). The queue destination is stubbed so the queue pipeline
     * (Koin, the store) stays out of the navigation test.
     */
    @Test
    fun `FR-23 summary review CTA opens the review queue`() =
        runComposeUiTest {
            setContent {
                val navController = rememberNavController()
                LaunchedEffect(navController) {
                    navController.navigate(Summary(kept = 0, queued = 3, skipped = 0))
                }
                PermissionFlowContent(
                    state =
                        MutableStateFlow(
                            PermissionUiState(
                                startScreen = PermissionScreen.HOME,
                                access = LibraryAccess.FULL,
                            ),
                        ),
                    onIntent = {},
                    navController = navController,
                    queueDestination = { BasicText("queue-stub") },
                )
            }
            waitForIdle()
            onNodeWithText("Review 3 to delete").performClick()
            waitForIdle()
            onNodeWithText("queue-stub").assertExists()
        }

    /**
     * WP-3.3: the DeleteSuccess route carries the committed count (FR-26) and
     * "Back home" returns to Home.
     */
    @Test
    fun `FR-26 delete success shows the count and back home returns home`() =
        runComposeUiTest {
            setContent {
                val navController = rememberNavController()
                LaunchedEffect(navController) {
                    navController.navigate(DeleteSuccess(deleted = 7))
                }
                PermissionFlowContent(
                    state =
                        MutableStateFlow(
                            PermissionUiState(
                                startScreen = PermissionScreen.HOME,
                                access = LibraryAccess.FULL,
                            ),
                        ),
                    onIntent = {},
                    navController = navController,
                )
            }
            waitForIdle()
            // The screen renders the count and the "Deleted" label as two
            // text nodes (a `display` number next to a colored label).
            onNodeWithText("7").assertExists()
            onNodeWithText("Deleted").assertExists()
            onNodeWithText("Back home").performClick()
            waitForIdle()
            onNodeWithText("Start shuffle").assertExists()
        }
}
