package dev.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.permission.PermissionIntent
import dev.swipe.presentation.permission.PermissionScreen
import dev.swipe.presentation.permission.PermissionUiState
import dev.swipe.presentation.permission.PermissionViewModel
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.presentation.swipe.SwipeViewModel
import dev.swipe.ui.components.ComponentGallery
import dev.swipe.ui.navigation.Denied
import dev.swipe.ui.navigation.Home
import dev.swipe.ui.navigation.Onboarding
import dev.swipe.ui.navigation.Swipe
import dev.swipe.ui.screens.home.HomeScreen
import dev.swipe.ui.screens.onboarding.DeniedScreen
import dev.swipe.ui.screens.onboarding.OnboardingScreen
import dev.swipe.ui.screens.swipe.SwipeScreen
import dev.swipe.ui.screens.swipe.decodeTypeFilter
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Root composable (REQUIREMENTS §5.8 screen map). Wraps the permission-driven
 * [NavHost] in the theme. The debug WP-1.2 [gallery] (ComponentGallery) is
 * only passed from debug entry points; release builds never reach it.
 */
@Composable
fun App(
    modifier: Modifier = Modifier,
    gallery: Boolean = false,
) {
    SwipeTheme {
        if (gallery) {
            ComponentGallery()
        } else {
            PermissionFlow(modifier = modifier)
        }
    }
}

/**
 * Connects [PermissionViewModel] to [PermissionFlowContent] and re-checks
 * authorization every time the app comes to the foreground, which is how a
 * grant made in Settings (FR-3) or a limited-library edit (FR-2) takes
 * effect. The flow and the handler are passed as stable instances so the
 * [NavHost] below is never recomposed with a new graph.
 */
@Composable
private fun PermissionFlow(
    modifier: Modifier = Modifier,
    viewModel: PermissionViewModel = koinViewModel(),
) {
    val onIntent: (PermissionIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onIntent(PermissionIntent.Recheck)
    }
    PermissionFlowContent(state = viewModel.uiState, onIntent = onIntent, modifier = modifier)
}

/**
 * The permission-flow [NavHost]. The graph is built once, with the start
 * destination named by the first non-null [PermissionUiState.startScreen]
 * (WP-1.7); `null` — the first authorization read in flight — shows the theme
 * background as a splash. Later routing is imperative: the host navigates to
 * the screen the state names and pops the initial route inclusively, so Back
 * cannot return to onboarding or Denied. Keeping `startDestination` and the
 * builder lambda's captures stable (the [state] flow is collected inside
 * [HomeContainer], never read in the builder) keeps navigation-compose from
 * rebuilding the graph on state updates, which would otherwise reset deeper
 * stacks once WP-1.9 adds routes. Split out from the ViewModel so the routing
 * is testable without Koin; [navController] is injectable for the same
 * reason.
 */
@Composable
internal fun PermissionFlowContent(
    state: StateFlow<PermissionUiState>,
    onIntent: (PermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    swipeDestination: @Composable (Swipe) -> Unit = { route -> SwipeDestination(route) },
) {
    val startScreen = state.collectAsState().value.startScreen
    if (startScreen == null) {
        Box(modifier = modifier.fillMaxSize().background(SwipeTheme.colors.bg))
        return
    }
    val currentRoute = startScreen.toRoute()
    val initialRoute = remember { currentRoute }
    LaunchedEffect(currentRoute) {
        if (currentRoute != initialRoute) {
            navController.navigate(currentRoute) {
                popUpTo(initialRoute::class) {
                    inclusive = true
                }
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = initialRoute,
        modifier = modifier.fillMaxSize(),
    ) {
        composable<Onboarding> {
            OnboardingScreen(onAllowAccess = { onIntent(PermissionIntent.RequestAccess) })
        }
        composable<Denied> {
            DeniedScreen(onOpenSettings = { onIntent(PermissionIntent.OpenSettings) })
        }
        composable<Home> {
            // The remaining §5.8 routes are declared in Routes.kt and get their
            // composable() entries from the work packages that own them.
            HomeContainer(
                state = state,
                onStartShuffle = { navController.navigate(ShuffleAllSwipe) },
                onManageLimitedAccess = { onIntent(PermissionIntent.ManageLimitedAccess) },
            )
        }
        composable<Swipe> { entry ->
            swipeDestination(entry.toRoute<Swipe>())
        }
    }
}

/**
 * The Swipe route for Home's "Shuffle everything" (FR-4): deck source `All`
 * serialized per the route contract, with the default ALL type filter (FR-5's
 * toggle arrives in WP-5.2). A constant, so it is built once and the NavHost
 * builder lambda captures nothing that changes.
 */
private val ShuffleAllSwipe =
    Swipe(
        sourceJson = Json.encodeToString(DeckSource.serializer(), DeckSource.All),
        typeFilter = MediaTypeFilter.ALL.name,
    )

/**
 * The real Swipe destination: decodes the route's [DeckSource] JSON and hands
 * to the Koin-backed [SwipeScreen]. Swappable in [PermissionFlowContent] so
 * navigation tests never build the deck pipeline.
 */
@Composable
private fun SwipeDestination(route: Swipe) {
    val source =
        remember(route.sourceJson) {
            Json.decodeFromString(DeckSource.serializer(), route.sourceJson)
        }
    SwipeContainer(source = source, typeFilter = decodeTypeFilter(route.typeFilter))
}

/**
 * Wires [SwipeViewModel] (resolved with the route's source and filter) and the
 * injected [MediaLoader] to [SwipeScreen]. State is collected inside this
 * destination composable, never in the NavHost builder (WP-1.7 rule).
 */
@Composable
private fun SwipeContainer(
    source: DeckSource,
    typeFilter: MediaTypeFilter,
    mediaLoader: MediaLoader = koinInject(),
) {
    val viewModel: SwipeViewModel =
        koinViewModel(
            parameters = { parametersOf(source, typeFilter) },
        )
    val onIntent: (SwipeIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    val state by viewModel.uiState.collectAsState()
    SwipeScreen(
        state = state,
        onIntent = onIntent,
        mediaLoader = mediaLoader,
    )
}

/**
 * Home destination that reads the FR-2 Limited-access count from the flow
 * inside the composition, so the NavHost builder lambda never captures state
 * values and the graph stays stable across `Recheck` updates.
 */
@Composable
private fun HomeContainer(
    state: StateFlow<PermissionUiState>,
    onStartShuffle: () -> Unit,
    onManageLimitedAccess: () -> Unit,
) {
    val ui by state.collectAsState()
    HomeScreen(
        onStartShuffle = onStartShuffle,
        limitedItemCount = ui.limitedCount,
        onManageLimitedAccess = onManageLimitedAccess,
    )
}

/** The route registered for a permission screen (§5.8 screen map). */
private fun PermissionScreen.toRoute(): Any =
    when (this) {
        PermissionScreen.ONBOARDING -> {
            Onboarding
        }

        PermissionScreen.DENIED -> {
            Denied
        }

        PermissionScreen.HOME -> {
            Home
        }
    }
