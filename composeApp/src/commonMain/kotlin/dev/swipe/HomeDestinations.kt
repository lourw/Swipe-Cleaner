package dev.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.albums.AlbumsEvent
import dev.swipe.presentation.albums.AlbumsIntent
import dev.swipe.presentation.albums.AlbumsViewModel
import dev.swipe.presentation.home.HomeEvent
import dev.swipe.presentation.home.HomeIntent
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
 * WP-5.3: this is the only collector of [HomeViewModel.events] (the events
 * are a channel — a second collector would steal them), turning each one
 * into navigation: [HomeEvent.OpenDeck] carries the tapped source *and* the
 * media-type filter selected at tap time (§5.8: "applies to the next deck",
 * FR-5), [HomeEvent.OpenAlbums] and [HomeEvent.OpenMonths] carry that filter
 * into the sub-screens so their counts and decks agree with Home (FR-5).
 * [homeViewModel] and the permission flow are injectable so navigation tests
 * run the real ViewModel over the fakes without Koin.
 */
@Composable
internal fun HomeDestination(
    navController: NavHostController,
    permissionState: StateFlow<PermissionUiState>,
    onManageLimitedAccess: () -> Unit,
    homeViewModel: HomeViewModel = koinViewModel(),
) {
    val onIntent: (HomeIntent) -> Unit = remember(homeViewModel) { homeViewModel::onIntent }
    val state by homeViewModel.uiState.collectAsState()
    val permission by permissionState.collectAsState()
    LaunchedEffect(homeViewModel) {
        homeViewModel.events.collect { event ->
            when (event) {
                is HomeEvent.OpenDeck -> navController.navigate(swipeRoute(event.source, event.typeFilter))
                is HomeEvent.OpenAlbums -> navController.navigate(Albums(event.typeFilter.name))
                is HomeEvent.OpenMonths -> navController.navigate(Months(event.typeFilter.name))
                HomeEvent.OpenQueue -> navController.navigate(Queue)
                HomeEvent.OpenSettings -> navController.navigate(Settings)
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
 * destination composable, never in the NavHost builder (WP-1.7 rule).
 */
@Composable
internal fun AlbumsDestination(
    route: Albums,
    navController: NavHostController,
    mediaLoader: MediaLoader = koinInject(),
) {
    val typeFilter = remember(route.typeFilter) { decodeTypeFilter(route.typeFilter) }
    val viewModel: AlbumsViewModel = koinViewModel(parameters = { parametersOf(typeFilter) })
    val onIntent: (AlbumsIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AlbumsEvent.OpenDeck -> navController.navigate(swipeRoute(event.source, event.typeFilter))
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
 * the screen open (NFR-4); its single event type opens the month deck.
 */
@Composable
internal fun MonthsDestination(
    route: Months,
    navController: NavHostController,
) {
    val typeFilter = remember(route.typeFilter) { decodeTypeFilter(route.typeFilter) }
    val viewModel: MonthsViewModel = koinViewModel(parameters = { parametersOf(typeFilter) })
    val onIntent: (MonthsIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MonthsEvent.OpenDeck -> navController.navigate(swipeRoute(event.source, event.typeFilter))
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
