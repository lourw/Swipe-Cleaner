// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalTestApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.swipe

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.usecase.LoadSourcesUseCase
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.presentation.home.HomeViewModel
import dev.swipe.presentation.permission.PermissionIntent
import dev.swipe.presentation.permission.PermissionScreen
import dev.swipe.presentation.permission.PermissionUiState
import dev.swipe.ui.navigation.Albums
import dev.swipe.ui.navigation.DeleteSuccess
import dev.swipe.ui.navigation.Months
import dev.swipe.ui.navigation.Summary
import dev.swipe.ui.navigation.Swipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

/**
 * WP-1.7 routing: [PermissionFlowContent] starts on the screen the
 * [PermissionUiState] names, forwards button taps as intents, and follows the
 * state when it routes onward (FR-1–3). WP-5.3 added Home's real destination
 * behind the [PermissionFlowContent.homeDestination] hook, so the Home-side
 * navigation tests (FR-4, FR-5, FR-10, FR-29) run the real
 * [HomeViewModel] over the fakes, with the sub-screen destinations stubbed to
 * capture the routes.
 */
class PermissionFlowContentTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-1 flow waits for the first authorization read`() =
        runComposeUiTest {
            setContent {
                PermissionFlowContent(state = MutableStateFlow(PermissionUiState()), onIntent = {})
            }
            onNodeWithText("Allow access").assertDoesNotExist()
            onNodeWithText("Shuffle everything").assertDoesNotExist()
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
            val state =
                MutableStateFlow(
                    PermissionUiState(
                        startScreen = PermissionScreen.HOME,
                        access = LibraryAccess.LIMITED,
                        limitedCount = 7,
                    ),
                )
            val home = HomeFixture(access = LibraryAccess.LIMITED)
            setContent {
                val nav = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = { intents.add(it) },
                    navController = nav,
                    homeDestination = {
                        HomeDestination(nav, state, { intents.add(PermissionIntent.ManageLimitedAccess) }, home.viewModel)
                    },
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
            val home = HomeFixture()
            setContent {
                val nav = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = { HomeDestination(nav, state, {}, home.viewModel) },
                )
            }
            onNodeWithText("Allow access").assertExists()

            state.value =
                PermissionUiState(
                    startScreen = PermissionScreen.HOME,
                    access = LibraryAccess.FULL,
                )
            waitForIdle()
            onNodeWithText("Shuffle everything").assertExists()
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
            val home = HomeFixture()
            setContent {
                controller = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = controller,
                    homeDestination = { HomeDestination(controller, state, {}, home.viewModel) },
                )
            }
            onNodeWithText("Allow access").assertExists()

            state.value =
                PermissionUiState(
                    startScreen = PermissionScreen.HOME,
                    access = LibraryAccess.FULL,
                )
            waitForIdle()
            onNodeWithText("Shuffle everything").assertExists()

            // System back lands here as popBackStack(); the last entry cannot
            // pop, so Home stays shown and no onboarding is left behind it.
            assertFalse(controller.popBackStack())
            onNodeWithText("Shuffle everything").assertExists()
            onNodeWithText("Allow access").assertDoesNotExist()
        }

    /**
     * WP-1.9 / WP-5.3: Home's **Shuffle everything** row runs through the real
     * [HomeViewModel] and navigates to the Swipe route with deck source `All`
     * (serialized per the route contract) and the ALL type filter. The deck
     * destination stays stubbed so the deck pipeline never starts.
     */
    @Test
    fun `FR-4 home shuffle navigates to the swipe deck with the All source`() =
        runComposeUiTest {
            var routed: Swipe? = null
            val home = HomeFixture()
            val state = homePermissionState()
            setContent {
                val nav = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = { HomeDestination(nav, state, {}, home.viewModel) },
                    swipeDestination = { route ->
                        routed = route
                        BasicText("deck-destination")
                    },
                )
            }
            onNodeWithText("Shuffle everything").performClick()
            waitForIdle()

            onNodeWithText("deck-destination").assertExists()
            val route = requireNotNull(routed)
            assertEquals(DeckSource.All, Json.decodeFromString(DeckSource.serializer(), route.sourceJson))
            assertEquals(MediaTypeFilter.ALL.name, route.typeFilter)
        }

    /**
     * WP-5.3 (FR-5): the Albums route carries the toggle selected on Home, so
     * the Albums counts and decks agree with it. The toggle switch runs
     * through the real ViewModel before the row is tapped.
     */
    @Test
    fun `FR-5 albums route carries the selected type filter`() =
        runComposeUiTest {
            var opened: Albums? = null
            val home = HomeFixture()
            val state = homePermissionState()
            setContent {
                val nav = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = { HomeDestination(nav, state, {}, home.viewModel) },
                    albumsDestination = { route ->
                        opened = route
                        BasicText("albums-destination")
                    },
                )
            }
            onNodeWithText("Videos").performClick()
            waitForIdle()
            onNodeWithText("Albums").performClick()
            waitForIdle()

            onNodeWithText("albums-destination").assertExists()
            assertEquals(MediaTypeFilter.VIDEOS.name, requireNotNull(opened).typeFilter)
        }

    /** WP-5.3 (FR-7): the By month route carries the Home filter too. */
    @Test
    fun `FR-7 months route carries the selected type filter`() =
        runComposeUiTest {
            var opened: Months? = null
            val home = HomeFixture()
            val state = homePermissionState()
            setContent {
                val nav = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = { HomeDestination(nav, state, {}, home.viewModel) },
                    monthsDestination = { route ->
                        opened = route
                        BasicText("months-destination")
                    },
                )
            }
            onNodeWithText("By month").performClick()
            waitForIdle()

            onNodeWithText("months-destination").assertExists()
            assertEquals(MediaTypeFilter.ALL.name, requireNotNull(opened).typeFilter)
        }

    /** WP-5.3 (FR-10): a non-empty queue surfaces the banner, which opens the queue. */
    @Test
    fun `FR-10 queue banner on home opens the review queue`() =
        runComposeUiTest {
            val home = HomeFixture(queued = 2)
            val state = homePermissionState()
            setContent {
                val nav = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = { HomeDestination(nav, state, {}, home.viewModel) },
                    queueDestination = { BasicText("queue-stub") },
                )
            }
            waitForIdle()
            onNodeWithText("🗑 2 waiting to delete ›").performClick()
            waitForIdle()
            onNodeWithText("queue-stub").assertExists()
        }

    /** WP-5.3 (FR-29): the Home gear reaches the Settings destination. */
    @Test
    fun `FR-29 settings gear opens the settings screen`() =
        runComposeUiTest {
            val home = HomeFixture()
            val state = homePermissionState()
            setContent {
                val nav = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = { HomeDestination(nav, state, {}, home.viewModel) },
                    // WP-6.1: the real destination builds SettingsViewModel (Koin);
                    // the navigation test stubs it, same as the queue stub.
                    settingsDestination = { BasicText("settings-stub") },
                )
            }
            onNodeWithContentDescription("Settings").performClick()
            waitForIdle()
            onNodeWithText("settings-stub").assertExists()
        }

    /**
     * WP-3.3: the summary's "Review N to delete" CTA navigates to the review
     * queue (FR-23). The queue destination is stubbed so the queue pipeline
     * (Koin, the store) stays out of the navigation test.
     */
    @Test
    fun `FR-23 summary review CTA opens the review queue`() =
        runComposeUiTest {
            val home = HomeFixture()
            val state = homePermissionState()
            setContent {
                val nav = rememberNavController()
                LaunchedEffect(nav) {
                    nav.navigate(Summary(kept = 0, queued = 3, skipped = 0))
                }
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = { HomeDestination(nav, state, {}, home.viewModel) },
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
            val home = HomeFixture()
            val state = homePermissionState()
            setContent {
                val nav = rememberNavController()
                LaunchedEffect(nav) {
                    nav.navigate(DeleteSuccess(deleted = 7))
                }
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = { HomeDestination(nav, state, {}, home.viewModel) },
                )
            }
            waitForIdle()
            // The screen renders the count and the "Deleted" label as two
            // text nodes (a `display` number next to a colored label).
            onNodeWithText("7").assertExists()
            onNodeWithText("Deleted").assertExists()
            onNodeWithText("Back home").performClick()
            waitForIdle()
            onNodeWithText("Shuffle everything").assertExists()
        }

    // ---- helpers ----

    private fun homePermissionState(): StateFlow<PermissionUiState> =
        MutableStateFlow(
            PermissionUiState(
                startScreen = PermissionScreen.HOME,
                access = LibraryAccess.FULL,
            ),
        )

    /**
     * The real [HomeViewModel] over the fakes, with [queued] items waiting in
     * the To Delete queue. Main is the unconfined test dispatcher (the
     * [dev.swipe.ui.screens.queue.QueueScreenTest] pattern) so the ViewModel's
     * scope runs inline on the UI test thread.
     */
    private class HomeFixture(
        access: LibraryAccess = LibraryAccess.FULL,
        queued: Int = 0,
    ) {
        val library = FakeMediaLibrary(photos(4), access = access)
        val store = FakeDecisionStore()
        val viewModel: HomeViewModel

        init {
            runBlocking {
                repeat(queued) { i -> store.record("queued-$i", Decision.DELETE, "session-1") }
            }
            Dispatchers.setMain(UnconfinedTestDispatcher())
            viewModel = HomeViewModel(library, store, LoadSourcesUseCase(library, store))
        }

        private companion object {
            fun photos(count: Int): List<MediaItem> =
                (1..count).map { i ->
                    MediaItem(
                        id = "asset-$i",
                        type = MediaType.PHOTO,
                        createdAt = Instant.fromEpochMilliseconds(i * 1000L),
                        durationMs = null,
                        widthPx = 120,
                        heightPx = 160,
                        isFavorite = false,
                        isScreenshot = false,
                        burstId = null,
                        isCloudOnly = null,
                    )
                }
        }
    }
}
