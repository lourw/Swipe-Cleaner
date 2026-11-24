package dev.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import dev.swipe.data.MediaLoader
import dev.swipe.data.NoPerfTrace
import dev.swipe.data.PerfSpan
import dev.swipe.data.PerfTrace
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.albums.AlbumsEvent
import dev.swipe.presentation.albums.AlbumsIntent
import dev.swipe.presentation.albums.AlbumsViewModel
import dev.swipe.presentation.home.HomeEvent
import dev.swipe.presentation.home.HomeIntent
import dev.swipe.presentation.home.HomeUiState
import dev.swipe.presentation.home.HomeViewModel
import dev.swipe.presentation.months.MonthsEvent
import dev.swipe.presentation.months.MonthsIntent
import dev.swipe.presentation.months.MonthsViewModel
import dev.swipe.presentation.permission.PermissionUiState
import dev.swipe.ui.navigation.Albums
import dev.swipe.ui.navigation.Months
import dev.swipe.ui.navigation.Queue
import dev.swipe.ui.navigation.Settings
import dev.swipe.ui.navigation.Swipe
import dev.swipe.ui.screens.home.AlbumsScreen
import dev.swipe.ui.screens.home.HomeScreen
import dev.swipe.ui.screens.home.MonthsScreen
import dev.swipe.ui.screens.swipe.decodeTypeFilter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Home destination that reads the FR-2 Limited-access count from the flow
 * inside the composition, so the NavHost builder lambda never captures state
 * values and the graph stays stable across `Recheck` updates.
 *
 * FR-1 (WP-8.7): first launch lands here while the iOS access prompt is still
 * undecided. Home then renders its NFR-4 placeholder state — the interactive
 * frame with em-dash counts, no fetch and no error — and the [HomeViewModel]
 * is mounted only once [PermissionUiState.access] decides, so every count
 * that arrives describes a library the app can actually read. [homeViewModel]
 * is a provider precisely so this gate can defer construction until then.
 *
 * WP-5.3: this is the only collector of [HomeViewModel.events] (the events
 * are a channel — a second collector would steal them), turning each one
 * into navigation: [HomeEvent.OpenDeck] carries the tapped source *and* the
 * media-type filter selected at tap time (§5.8: "applies to the next deck",
 * FR-5), [HomeEvent.OpenAlbums] and [HomeEvent.OpenMonths] carry that filter
 * into the sub-screens so their counts and decks agree with Home (FR-5).
 * The permission flow and the ViewModel provider are injectable so navigation
 * tests run the real ViewModel over the fakes without Koin.
 */
@Composable
internal fun HomeDestination(
    navController: NavHostController,
    permissionState: StateFlow<PermissionUiState>,
    onManageLimitedAccess: () -> Unit,
    perfTrace: PerfTrace = NoPerfTrace,
    homeViewModel: @Composable () -> HomeViewModel = { koinViewModel() },
) {
    val permission by permissionState.collectAsState()
    // NFR-4: Home's first composition is the interactive frame — the type
    // toggle and rows are on screen, only counts may still stream in (the
    // progressive ladder keeps them out of the critical path, §6.5). Best
    // effort at the composable level, per WP-6.3; docs/perf.md.
    LaunchedEffect(Unit) { perfTrace.end(PerfSpan.COLD_START_HOME) }
    if (permission.access == LibraryAccess.NOT_DETERMINED) {
        // FR-1: the system prompt shows over this placeholder; row taps have
        // nothing to load yet, so intents are dropped until access decides.
        HomeScreen(
            state = HomeUiState(),
            onIntent = {},
            limitedItemCount = null,
            onManageLimitedAccess = onManageLimitedAccess,
        )
        return
    }
    val viewModel = homeViewModel()
    val onIntent: (HomeIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    val state by viewModel.uiState.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycle) {
        viewModel.events.collect { event ->
            when (event) {
                // The deck route describes the tapped row (WP-9.6): single-top
                // so the double-tap race cannot stack a second copy.
                is HomeEvent.OpenDeck ->
                    navController.navigateIfResumed(lifecycle, swipeRoute(event.source, event.typeFilter), singleTop = true)
                is HomeEvent.OpenAlbums -> navController.navigateIfResumed(lifecycle, Albums(event.typeFilter.name))
                is HomeEvent.OpenMonths -> navController.navigateIfResumed(lifecycle, Months(event.typeFilter.name))
                HomeEvent.OpenQueue -> navController.navigateIfResumed(lifecycle, Queue)
                HomeEvent.OpenSettings -> navController.navigateIfResumed(lifecycle, Settings)
            }
        }
    }
    HomeScreen(
        state = state,
        onIntent = onIntent,
        limitedItemCount = permission.limitedCount,
        onManageLimitedAccess = onManageLimitedAccess,
    )
}

/**
 * The Albums destination (FR-8, WP-5.3): [AlbumsViewModel] is resolved with
 * the route's filter (same contract as the deck), and its single event type
 * opens the album deck with that filter. State is collected inside this
 * destination composable, never in the NavHost builder (WP-1.7 rule). The
 * ViewModel and [mediaLoader] providers let navigation tests run the real
 * ViewModel over the fakes without Koin (WP-9.6); event-driven navigation
 * happens only while this entry is resumed, so a double tap on a row pushes
 * exactly one deck (WP-9.6, FR-8).
 */
@Composable
internal fun AlbumsDestination(
    route: Albums,
    navController: NavHostController,
    mediaLoader: MediaLoader = koinInject(),
    albumsViewModel: @Composable () -> AlbumsViewModel = {
        koinViewModel(parameters = { parametersOf(decodeTypeFilter(route.typeFilter)) })
    },
) {
    val viewModel: AlbumsViewModel = albumsViewModel()
    val onIntent: (AlbumsIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    val state by viewModel.uiState.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycle) {
        viewModel.events.collect { event ->
            when (event) {
                is AlbumsEvent.OpenDeck ->
                    navController.navigateIfResumed(lifecycle, swipeRoute(event.source, event.typeFilter), singleTop = true)
            }
        }
    }
    AlbumsScreen(
        state = state,
        onIntent = onIntent,
        mediaLoader = mediaLoader,
        onBack = { navController.navigateUp() },
    )
}

/**
 * The By month destination (FR-7, WP-5.3): [MonthsViewModel] is resolved
 * with the route's filter and pays for the creation-date scan only now, with
 * the screen open (NFR-4); its single event type opens the month deck. The
 * ViewModel provider keeps the double-tap navigation test (WP-9.6, FR-7)
 * able to run the real ViewModel without Koin.
 */
@Composable
internal fun MonthsDestination(
    route: Months,
    navController: NavHostController,
    monthsViewModel: @Composable () -> MonthsViewModel = {
        koinViewModel(parameters = { parametersOf(decodeTypeFilter(route.typeFilter)) })
    },
) {
    val viewModel: MonthsViewModel = monthsViewModel()
    val onIntent: (MonthsIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    val state by viewModel.uiState.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycle) {
        viewModel.events.collect { event ->
            when (event) {
                is MonthsEvent.OpenDeck ->
                    navController.navigateIfResumed(lifecycle, swipeRoute(event.source, event.typeFilter), singleTop = true)
            }
        }
    }
    MonthsScreen(
        state = state,
        onIntent = onIntent,
        onBack = { navController.navigateUp() },
    )
}

/**
 * WP-9.6 (FR-7, FR-8, NFR-4): navigate only while [owner] — the lifecycle of
 * the back-stack entry whose events these are — is at least
 * [Lifecycle.State.RESUMED]. A double tap on a row lands two events; the
 * first navigation moves this screen off RESUMED, and the second event must
 * not stack a second deck onto the session. An event that arrives while the
 * screen is not resumed is dropped, not queued: its row is behind whatever
 * screen is open now, so a queued navigation could only ever surprise the
 * user later.
 *
 * [singleTop] covers the race the lifecycle cannot: both taps land before
 * the first navigation settles, so both events still see RESUMED. For a deck
 * the route fully describes the tapped row, so a second identical
 * navigation reuses the entry the first one pushed instead of stacking a
 * copy of it.
 */
private fun NavHostController.navigateIfResumed(
    owner: LifecycleOwner,
    route: Any,
    singleTop: Boolean = false,
) {
    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        navigate(route) { launchSingleTop = singleTop }
    }
}

/**
 * The Swipe route for one deck request (§6.2): the [source] serialized per
 * the route contract, with the media-type filter [typeFilter] that applied
 * when the row was tapped (FR-5).
 */
internal fun swipeRoute(
    source: DeckSource,
    typeFilter: MediaTypeFilter,
) = Swipe(
    sourceJson = Json.encodeToString(DeckSource.serializer(), source),
    typeFilter = typeFilter.name,
)
