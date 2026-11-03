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
import dev.swipe.domain.usecase.CommitDeletionsUseCase
import dev.swipe.domain.usecase.PruneQueueUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.RestoreFromQueueUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import dev.swipe.presentation.queue.QueueViewModel
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
        // WP-3.1: the review-queue trio. CommitDeletionsUseCase resolving through the
        // real graph is the NFR-5 wiring check — it is the only MediaLibrary.delete
        // caller, so a missing binding would break the delete path at runtime.
        koin.get<CommitDeletionsUseCase>()
        koin.get<PruneQueueUseCase>()
        koin.get<RestoreFromQueueUseCase>()
    }

    @OptIn(ExperimentalTestApi::class)
    @Suppress("DEPRECATION") // v1 runComposeUiTest, consistent with the rest of the suite.
    @Test
    fun `queue view model definition resolves with no route parameters`() {
        startKoin { modules(commonModule, iosModule) }
        var resolved: QueueViewModel? = null
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides ThrowawayStoreOwner) {
                    // The Queue route carries no parameters (WP-3.1), so this must
                    // resolve with an empty parameter list. runComposeUiTest's virtual
                    // clock starts paused, so the ViewModel's prune-and-collect init
                    // stays suspended and never touches PhotoKit or the live driver.
                    resolved = koinViewModel<QueueViewModel>()
                }
            }
        }
        assertTrue(resolved != null)
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
