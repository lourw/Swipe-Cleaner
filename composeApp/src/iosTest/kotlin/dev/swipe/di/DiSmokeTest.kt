package dev.swipe.di

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.swipe.data.DecisionStore
import dev.swipe.data.Haptics
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaLoader
import dev.swipe.data.SessionStore
import dev.swipe.data.SettingsStore
import dev.swipe.data.SystemActions
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import dev.swipe.presentation.swipe.SwipeViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Resolves every binding the running app needs. The WP-1.9 on-device check found that
 * `single { Clock.System }` registered the companion subtype instead of [Clock], which
 * no logic test could see (FR-20); this test is the regression guard for that class of
 * wiring bug — the Koin graph must resolve end to end on the real platform.
 */
class DiSmokeTest {
    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `koin graph resolves every app binding`() {
        startKoin { modules(commonModule, iosModule) }
        val koin = KoinPlatform.getKoin()
        koin.get<Clock>()
        koin.get<DecisionStore>()
        koin.get<SessionStore>()
        koin.get<MediaLibrary>()
        koin.get<MediaLoader>()
        koin.get<SettingsStore>()
        koin.get<SystemActions>()
        koin.get<Haptics>()
        koin.get<BuildDeckUseCase>()
        koin.get<RecordDecisionUseCase>()
        koin.get<UndoDecisionUseCase>()
    }

    @OptIn(ExperimentalTestApi::class)
    @Suppress("DEPRECATION") // v1 runComposeUiTest, consistent with the rest of the suite.
    @Test
    fun `swipe view model definition resolves its route parameters`() {
        startKoin { modules(commonModule, iosModule) }
        var resolved: SwipeViewModel? = null
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides ThrowawayStoreOwner) {
                    // Exercises the same `viewModel { params -> }` definition the deck
                    // screen uses, so a wrong parameter type fails here. runComposeUiTest's
                    // virtual clock starts paused, so the ViewModel's init coroutines stay
                    // suspended and never touch PhotoKit or the live SQLite driver.
                    resolved =
                        koinViewModel<SwipeViewModel> {
                            parametersOf(DeckSource.All, MediaTypeFilter.ALL)
                        }
                }
            }
        }
        assertTrue(resolved != null)
    }
}

/** Minimal owner so the definition under test builds outside any screen. */
private val ThrowawayStoreOwner =
    object : androidx.lifecycle.ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
