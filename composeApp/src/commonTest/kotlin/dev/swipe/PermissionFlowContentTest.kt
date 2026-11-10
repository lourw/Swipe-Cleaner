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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * WP-1.7 routing: [PermissionFlowContent] starts on the screen the
 * [PermissionUiState] names, forwards button taps as intents, and follows the
 * state when it routes onward (FR-1–3). WP-5.3 added Home's real destination
 * behind the [PermissionFlowContent.homeDestination] hook, so the Home-side
 * navigation tests (FR-4, FR-5, FR-10, FR-29) run the real
 * [HomeViewModel] over the fakes, with the sub-screen destinations stubbed to
 * capture the routes. WP-8.7 (FR-1): the flow opens straight to Home; while
 * access is undecided Home shows its placeholder and mounts nothing.
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
            onNodeWithText("Shuffle everything").assertDoesNotExist()
            onNodeWithText("Open Settings").assertDoesNotExist()
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
                        HomeDestination(
                            nav,
                            state,
                            { intents.add(PermissionIntent.ManageLimitedAccess) },
                            homeViewModel = { home.viewModel },
                        )
                    },
                )
            }
            onNodeWithText("You've given access to 7 items.").assertExists()
            onNodeWithText("Manage").performClick()
            assertEquals(listOf<PermissionIntent>(PermissionIntent.ManageLimitedAccess), intents)
        }

    /**
     * FR-1 + NFR-4: first launch is Home with the prompt over it — the
     * interactive frame with placeholder counts, and the library stays
     * untouched: the [HomeViewModel] is not even mounted while access is
     * undecided.
     */
    @Test
    fun `FR-1 home renders placeholders without fetching while access is undecided`() =
        runComposeUiTest {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val library = FakeMediaLibrary(photos(4), access = LibraryAccess.NOT_DETERMINED)
            val store = FakeDecisionStore()
            var mounted = false
            val state =
                MutableStateFlow(
                    PermissionUiState(
                        startScreen = PermissionScreen.HOME,
                        access = LibraryAccess.NOT_DETERMINED,
                    ),
                )
            setContent {
                val nav = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = {
                        HomeDestination(
                            nav,
                            state,
                            {},
                            homeViewModel = {
                                mounted = true
                                HomeViewModel(library, store, LoadSourcesUseCase(library, store))
                            },
                        )
                    },
                )
            }
            waitForIdle()
            onNodeWithText("Shuffle everything").assertExists()
            onNodeWithText("4").assertDoesNotExist() // placeholder, not a count (NFR-4)
            assertFalse(mounted)
            assertEquals(0, library.assetIdsCalls.size)
            assertEquals(0, library.albumsCalls)
            assertEquals(0, library.smartCountsCalls)
        }

    /**
     * FR-1: answering the prompt with Allow Full Access while Home is on
     * screen mounts the real [HomeViewModel] and the counts stream in.
     */
    @Test
    fun `FR-1 home loads its counts once access is granted`() =
        runComposeUiTest {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val library = FakeMediaLibrary(photos(4), access = LibraryAccess.FULL)
            val store = FakeDecisionStore()
            var built: HomeViewModel? = null
            val state =
                MutableStateFlow(
                    PermissionUiState(
                        startScreen = PermissionScreen.HOME,
                        access = LibraryAccess.NOT_DETERMINED,
                    ),
                )
            setContent {
                val nav = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = nav,
                    homeDestination = {
                        HomeDestination(
                            nav,
                            state,
                            {},
                            homeViewModel = {
                                built ?: HomeViewModel(library, store, LoadSourcesUseCase(library, store))
                                    .also { built = it }
                            },
                        )
                    },
                )
            }
            waitForIdle()
            assertNull(built)
            state.value =
                PermissionUiState(
                    startScreen = PermissionScreen.HOME,
                    access = LibraryAccess.FULL,
                )
            waitForIdle()
            assertNotNull(built)
            onAllNodesWithText("4").onFirst().assertExists() // deck counts stream in (FR-4)
            assertTrue(library.assetIdsCalls.isNotEmpty())
        }

    /**
     * FR-1: Home is the root from the first frame, so the system back
     * gesture — `popBackStack()` — is a no-op and cannot leave the app or
     * uncover a permission screen.
     */
    @Test
    fun `FR-1 back cannot leave Home on first launch`() =
        runComposeUiTest {
            lateinit var controller: NavHostController
            val home = HomeFixture()
            val state =
                MutableStateFlow(
                    PermissionUiState(
                        startScreen = PermissionScreen.HOME,
                        access = LibraryAccess.NOT_DETERMINED,
                    ),
                )
            setContent {
                controller = rememberNavController()
                PermissionFlowContent(
                    state = state,
                    onIntent = {},
                    navController = controller,
                    homeDestination = { HomeDestination(controller, state, {}, homeViewModel = { home.viewModel }) },
                )
            }
            onNodeWithText("Shuffle everything").assertExists()

            state.value =
                PermissionUiState(
                    startScreen = PermissionScreen.HOME,
                    access = LibraryAccess.FULL,
                )
            waitForIdle()

            // System back lands here as popBackStack(); Home is the only
            // entry on the stack, so nothing pops (FR-1).
            assertFalse(controller.popBackStack())
            onNodeWithText("Shuffle everything").assertExists()
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
                    homeDestination = { HomeDestination(nav, state, {}, homeViewModel = { home.viewModel }) },
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
                    homeDestination = { HomeDestination(nav, state, {}, homeViewModel = { home.viewModel }) },
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
                    homeDestination = { HomeDestination(nav, state, {}, homeViewModel = { home.viewModel }) },
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
                    homeDestination = { HomeDestination(nav, state, {}, homeViewModel = { home.viewModel }) },
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
                    homeDestination = { HomeDestination(nav, state, {}, homeViewModel = { home.viewModel }) },
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
                    homeDestination = { HomeDestination(nav, state, {}, homeViewModel = { home.viewModel }) },
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
                    homeDestination = { HomeDestination(nav, state, {}, homeViewModel = { home.viewModel }) },
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
    }
}

private fun photos(count: Int): List<MediaItem> =
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
