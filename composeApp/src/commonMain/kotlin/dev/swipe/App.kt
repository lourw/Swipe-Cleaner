package dev.swipe

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import dev.swipe.data.Haptics
import dev.swipe.data.MediaLoader
import dev.swipe.data.SettingsStore
import dev.swipe.data.SystemActions
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.permission.PermissionIntent
import dev.swipe.presentation.permission.PermissionScreen
import dev.swipe.presentation.permission.PermissionUiState
import dev.swipe.presentation.permission.PermissionViewModel
import dev.swipe.presentation.queue.QueueIntent
import dev.swipe.presentation.queue.QueueViewModel
import dev.swipe.presentation.settings.SettingsIntent
import dev.swipe.presentation.settings.SettingsViewModel
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.presentation.swipe.SwipeViewModel
import dev.swipe.ui.components.ComponentGallery
import dev.swipe.ui.navigation.Albums
import dev.swipe.ui.navigation.DeleteSuccess
import dev.swipe.ui.navigation.Denied
import dev.swipe.ui.navigation.Home
import dev.swipe.ui.navigation.Months
import dev.swipe.ui.navigation.Onboarding
import dev.swipe.ui.navigation.Queue
import dev.swipe.ui.navigation.Settings
import dev.swipe.ui.navigation.Summary
import dev.swipe.ui.navigation.Swipe
import dev.swipe.ui.screens.onboarding.DeniedScreen
import dev.swipe.ui.screens.onboarding.OnboardingScreen
import dev.swipe.ui.screens.queue.QueueScreen
import dev.swipe.ui.screens.settings.SettingsScreen
import dev.swipe.ui.screens.summary.DeleteSuccessScreen
import dev.swipe.ui.screens.swipe.SummaryScreen
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
    systemActions: SystemActions = koinInject(),
) {
    val onIntent: (PermissionIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onIntent(PermissionIntent.Recheck)
    }
    // remember: the flow re-registers a NSNotification observer per call, so
    // building it inline would churn the subscription on every recomposition
    // (same rule as SwipeContainer).
    val reduceMotionFlow = remember(systemActions) { systemActions.isReduceMotionEnabled() }
    val reduceMotion by reduceMotionFlow.collectAsState(initial = false)
    PermissionFlowContent(
        state = viewModel.uiState,
        onIntent = onIntent,
        reduceMotion = reduceMotion,
        modifier = modifier,
    )
}

/**
 * Navigation transition durations (milliseconds): the Reduce Motion
 * crossfade matches the §5.6 card crossfade; the slide is the iOS-style
 * push, with the vacating screen parallaxing at `1 / NAV_EXIT_PARALLAX_DIVISOR`
 * of the travel.
 */
private const val NAV_CROSSFADE_MS = 150
private const val NAV_SLIDE_MS = 350
private const val NAV_EXIT_PARALLAX_DIVISOR = 3

/**
 * The permission-flow [NavHost]. The graph is built once, with the start
 * destination named by the first non-null [PermissionUiState.startScreen]
 * (WP-1.7); `null` — the first authorization read in flight — shows the theme
 * background as a splash. Later routing is imperative: the host navigates to
 * the screen the state names and pops the initial route inclusively, so Back
 * cannot return to onboarding or Denied. Keeping `startDestination` and the
 * builder lambda's captures stable (the [state] flow is collected inside
 * [HomeDestination], never read in the builder) keeps navigation-compose from
 * rebuilding the graph on state updates, which would otherwise reset deeper
 * stacks once WP-1.9 adds routes. Split out from the ViewModel so the routing
 * is testable without Koin; [navController] is injectable for the same
 * reason, as are the Koin-backed destinations ([homeDestination],
 * [albumsDestination], [monthsDestination], [swipeDestination],
 * [queueDestination], [settingsDestination]).
 *
 * §5.6 Reduce Motion: screen changes slide while motion is on and
 * crossfade over [NAV_CROSSFADE_MS] with [reduceMotion] — the same
 * "crossfade instead of move" language the card commit uses.
 */
@Composable
internal fun PermissionFlowContent(
    state: StateFlow<PermissionUiState>,
    onIntent: (PermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    navController: NavHostController = rememberNavController(),
    swipeDestination: @Composable (Swipe) -> Unit = { route -> SwipeDestination(route, navController) },
    queueDestination: @Composable () -> Unit = { QueueContainer(navController) },
    homeDestination: @Composable () -> Unit = {
        HomeDestination(navController, state, { onIntent(PermissionIntent.ManageLimitedAccess) })
    },
    albumsDestination: @Composable (Albums) -> Unit = { route -> AlbumsDestination(route, navController) },
    monthsDestination: @Composable (Months) -> Unit = { route -> MonthsDestination(route, navController) },
    settingsDestination: @Composable () -> Unit = { SettingsContainer(navController) },
) {
    val startScreen = state.collectAsState().value.startScreen
    if (startScreen == null) {
        Box(modifier = modifier.fillMaxSize().background(SwipeTheme.colors.bg))
        return
    }
    val currentRoute = startScreen.toRoute()
    val initialRoute = remember { currentRoute }
    FollowStartRoute(navController, currentRoute, initialRoute)
    NavHost(
        navController = navController,
        startDestination = initialRoute,
        modifier = modifier.fillMaxSize(),
        // §5.6 Reduce Motion: push/pop slides become crossfades.
        enterTransition = {
            if (reduceMotion) {
                fadeIn(tween(NAV_CROSSFADE_MS))
            } else {
                slideInHorizontally(tween(NAV_SLIDE_MS)) { it }
            }
        },
        exitTransition = {
            if (reduceMotion) {
                fadeOut(tween(NAV_CROSSFADE_MS))
            } else {
                slideOutHorizontally(tween(NAV_SLIDE_MS)) { -it / NAV_EXIT_PARALLAX_DIVISOR }
            }
        },
        popEnterTransition = {
            if (reduceMotion) {
                fadeIn(tween(NAV_CROSSFADE_MS))
            } else {
                slideInHorizontally(tween(NAV_SLIDE_MS)) { -it / NAV_EXIT_PARALLAX_DIVISOR }
            }
        },
        popExitTransition = {
            if (reduceMotion) {
                fadeOut(tween(NAV_CROSSFADE_MS))
            } else {
                slideOutHorizontally(tween(NAV_SLIDE_MS)) { it }
            }
        },
    ) {
        composable<Onboarding> {
            OnboardingScreen(onAllowAccess = { onIntent(PermissionIntent.RequestAccess) })
        }
        composable<Denied> {
            DeniedScreen(onOpenSettings = { onIntent(PermissionIntent.OpenSettings) })
        }
        composable<Home> {
            // The WP-5.3 destination (HomeViewModel-backed) is behind a hook so
            // navigation tests can run it over fakes without Koin, matching
            // [swipeDestination] and [queueDestination].
            homeDestination()
        }
        composable<Albums> { entry ->
            albumsDestination(entry.toRoute<Albums>())
        }
        composable<Months> { entry ->
            monthsDestination(entry.toRoute<Months>())
        }
        composable<Settings> {
            // The WP-6.1 destination (FR-29–31) behind a hook so navigation tests
            // never build the settings pipeline, matching [queueDestination].
            settingsDestination()
        }
        composable<Swipe> { entry ->
            swipeDestination(entry.toRoute<Swipe>())
        }
        composable<Summary> { entry ->
            val route = entry.toRoute<Summary>()
            SummaryScreen(
                kept = route.kept,
                queued = route.queued,
                skipped = route.skipped,
                onBackHome = { navController.backToHome() },
                onReviewQueue = { navController.navigate(Queue) },
            )
        }
        composable<Queue> {
            queueDestination()
        }
        composable<DeleteSuccess> { entry ->
            val route = entry.toRoute<DeleteSuccess>()
            DeleteSuccessScreen(
                deleted = route.deleted,
                onBackHome = { navController.backToHome() },
            )
        }
    }
}

/**
 * The imperative half of WP-1.7 routing: once the state's start screen moves
 * off the initial route, navigate to it and pop the initial entry inclusively
 * so Back cannot return to onboarding or Denied (FR-1, FR-3).
 */
@Composable
private fun FollowStartRoute(
    navController: NavHostController,
    currentRoute: Any,
    initialRoute: Any,
) {
    LaunchedEffect(currentRoute) {
        if (currentRoute != initialRoute) {
            navController.navigate(currentRoute) {
                popUpTo(initialRoute::class) {
                    inclusive = true
                }
            }
        }
    }
}

/**
 * Home, single-top, with everything above it popped: the Close button's
 * destination from the deck (FR-20, §6.5 resume) and the summary's
 * "Back home" (FR-21).
 * From a deep link with no Home on the stack this just creates it.
 */
private fun NavHostController.backToHome() {
    navigate(Home) {
        popUpTo(Home::class) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * The real Swipe destination: decodes the route's [DeckSource] JSON and hands
 * to the Koin-backed [SwipeScreen]. Swappable in [PermissionFlowContent] so
 * navigation tests never build the deck pipeline.
 */
@Composable
private fun SwipeDestination(
    route: Swipe,
    navController: NavHostController,
) {
    val source =
        remember(route.sourceJson) {
            Json.decodeFromString(DeckSource.serializer(), route.sourceJson)
        }
    SwipeContainer(
        source = source,
        typeFilter = decodeTypeFilter(route.typeFilter),
        navController = navController,
        onClose = { navController.backToHome() },
        onOpenQueue = { navController.navigate(Queue) },
    )
}

/**
 * Wires [SwipeViewModel] (resolved with the route's source and filter) and the
 * injected [MediaLoader], [SystemActions] (Reduce Motion, §5.6) and [Haptics]
 * (§5.6) to [SwipeScreen]. State is collected inside this destination
 * composable, never in the NavHost builder (WP-1.7 rule).
 *
 * FR-21: when the deck ends, the summary replaces the deck route — the deck
 * leaves the back stack, so Back from the summary cannot resume a finished
 * session. The counts ride the route (they are final the moment the deck ends).
 */
@Composable
private fun SwipeContainer(
    source: DeckSource,
    typeFilter: MediaTypeFilter,
    navController: NavHostController,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    mediaLoader: MediaLoader = koinInject(),
    systemActions: SystemActions = koinInject(),
    haptics: Haptics = koinInject(),
    settings: SettingsStore = koinInject(),
) {
    val viewModel: SwipeViewModel =
        koinViewModel(
            parameters = { parametersOf(source, typeFilter) },
        )
    val onIntent: (SwipeIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    val state by viewModel.uiState.collectAsState()
    // remember: the flow re-registers a NSNotification observer per call, so
    // building it inline would churn the subscription on every recomposition.
    val reduceMotionFlow = remember(systemActions) { systemActions.isReduceMotionEnabled() }
    val reduceMotion by reduceMotionFlow.collectAsState(initial = false)
    // FR-16/FR-29: with autoplay off the card shows the poster and a play
    // affordance until tapped.
    val autoplayVideos by settings.autoplayVideos.collectAsState()
    val finished = state.finished
    LaunchedEffect(finished) {
        if (finished != null) {
            navController.navigate(Summary(kept = finished.kept, queued = finished.queued, skipped = finished.skipped)) {
                popUpTo(Swipe::class) { inclusive = true }
            }
        }
    }
    SwipeScreen(
        state = state,
        events = viewModel.events,
        onIntent = onIntent,
        mediaLoader = mediaLoader,
        haptics = haptics,
        reduceMotion = reduceMotion,
        onClose = onClose,
        onOpenQueue = onOpenQueue,
        autoplayVideos = autoplayVideos,
    )
}

/**
 * Wires [QueueViewModel] and the injected [MediaLoader] to [QueueScreen]
 * (FR-23–28). State is collected inside this destination composable, never
 * in the NavHost builder (WP-1.7 rule). This is the only collector of the
 * ViewModel's navigation events: [QueueEvent.ShowDeleteSuccess] replaces the
 * queue route with the delete-success screen, so Back from the success
 * screen cannot return to a stale queue (FR-26). The confirm dialog and
 * toasts stay inside the screen; only navigation leaves through here.
 */
@Composable
private fun QueueContainer(
    navController: NavHostController,
    mediaLoader: MediaLoader = koinInject(),
) {
    val viewModel: QueueViewModel = koinViewModel()
    val onIntent: (QueueIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    val state by viewModel.uiState.collectAsState()
    QueueScreen(
        state = state,
        events = viewModel.events,
        onIntent = onIntent,
        mediaLoader = mediaLoader,
        onBack = { navController.navigateUp() },
        onBackHome = { navController.backToHome() },
        onShowDeleteSuccess = { deleted ->
            navController.navigate(DeleteSuccess(deleted)) {
                popUpTo(Queue::class) { inclusive = true }
            }
        },
    )
}

/**
 * Wires [SettingsViewModel] to [SettingsScreen] (FR-29–31). State is collected
 * inside this destination composable, never in the NavHost builder (WP-1.7
 * rule). The screen owns the confirm dialog and toasts; this container only
 * supplies the ViewModel and Back.
 */
@Composable
private fun SettingsContainer(navController: NavHostController) {
    val viewModel: SettingsViewModel = koinViewModel()
    val onIntent: (SettingsIntent) -> Unit = remember(viewModel) { viewModel::onIntent }
    val state by viewModel.uiState.collectAsState()
    SettingsScreen(
        state = state,
        events = viewModel.events,
        onIntent = onIntent,
        onBack = { navController.navigateUp() },
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
