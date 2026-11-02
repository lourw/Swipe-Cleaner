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
import dev.swipe.presentation.permission.PermissionIntent
import dev.swipe.presentation.permission.PermissionScreen
import dev.swipe.presentation.permission.PermissionUiState
import dev.swipe.presentation.permission.PermissionViewModel
import dev.swipe.ui.components.ComponentGallery
import dev.swipe.ui.navigation.Denied
import dev.swipe.ui.navigation.Home
import dev.swipe.ui.navigation.Onboarding
import dev.swipe.ui.screens.home.HomeScreen
import dev.swipe.ui.screens.onboarding.DeniedScreen
import dev.swipe.ui.screens.onboarding.OnboardingScreen
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.viewmodel.koinViewModel

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
            // "Start shuffle" starts the deck in WP-1.9; the other routes in
            // the §5.8 screen map are declared in Routes.kt and get their
            // composable() entries from the work packages that own them.
            HomeContainer(
                state = state,
                onManageLimitedAccess = { onIntent(PermissionIntent.ManageLimitedAccess) },
            )
        }
    }
}

/**
 * Home destination that reads the FR-2 Limited-access count from the flow
 * inside the composition, so the NavHost builder lambda never captures state
 * values and the graph stays stable across `Recheck` updates.
 */
@Composable
private fun HomeContainer(
    state: StateFlow<PermissionUiState>,
    onManageLimitedAccess: () -> Unit,
) {
    val ui by state.collectAsState()
    HomeScreen(
        onStartShuffle = {},
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
