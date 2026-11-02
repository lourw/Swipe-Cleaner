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
import androidx.navigation.NavGraph.Companion.findStartDestination
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
 * effect.
 */
@Composable
private fun PermissionFlow(
    modifier: Modifier = Modifier,
    viewModel: PermissionViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onIntent(PermissionIntent.Recheck)
    }
    PermissionFlowContent(state = state, onIntent = viewModel::onIntent, modifier = modifier)
}

/**
 * The permission-flow [NavHost]. The start destination comes from
 * [PermissionUiState.startScreen] (WP-1.7); `null` — the first authorization
 * read in flight — shows the theme background as a splash. When the state
 * routes to a different screen than the host started on (for example after
 * the system prompt on Onboarding), the host navigates there and pops the
 * start destination so Back cannot return to it. Split out from the
 * ViewModel so the routing is testable without Koin.
 */
@Composable
internal fun PermissionFlowContent(
    state: PermissionUiState,
    onIntent: (PermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val startScreen = state.startScreen
    if (startScreen == null) {
        Box(modifier = modifier.fillMaxSize().background(SwipeTheme.colors.bg))
        return
    }
    val startRoute =
        when (startScreen) {
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
    val navController = rememberNavController()
    val initialRoute = remember { startRoute }
    LaunchedEffect(startRoute) {
        if (startRoute != initialRoute) {
            navController.navigate(startRoute) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = true
                }
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = startRoute,
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
            HomeScreen(
                onStartShuffle = {},
                limitedItemCount = state.limitedCount,
                onManageLimitedAccess = { onIntent(PermissionIntent.ManageLimitedAccess) },
            )
        }
    }
}
