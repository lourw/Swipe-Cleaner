package dev.swipe.di

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.swipe.data.DecisionStore
import dev.swipe.data.Haptics
import dev.swipe.data.MediaDeleter
import dev.swipe.data.MediaHider
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaLoader
import dev.swipe.data.MediaSizing
import dev.swipe.data.SessionStore
import dev.swipe.data.SettingsStore
import dev.swipe.data.SystemActions
import dev.swipe.data.VideoPreloader
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DeleteResult
import dev.swipe.domain.model.HideResult
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.CommitDeletionsUseCase
import dev.swipe.domain.usecase.PruneQueueUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.RestoreFromQueueUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import dev.swipe.platform.PhotoKitMediaLibrary
import dev.swipe.platform.VideoItemCache
import dev.swipe.presentation.albums.AlbumsViewModel
import dev.swipe.presentation.home.HomeViewModel
import dev.swipe.presentation.months.MonthsViewModel
import dev.swipe.presentation.permission.PermissionViewModel
import dev.swipe.presentation.queue.QueueViewModel
import dev.swipe.presentation.settings.SettingsViewModel
import dev.swipe.presentation.swipe.SwipeViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
        koin.get<MediaDeleter>()
        // FR-38: the hide door resolves through the real graph, too. Unlike the
        // delete door it is not a view of PhotoKitMediaLibrary — PhotoKitMediaHider
        // is its own implementation — so there is no assertSame to draw here.
        koin.get<MediaHider>()
        // FR-34: the sizing door resolves too — its own implementation again,
        // reading PHAssetResource sizes beside (never inside) the library.
        koin.get<MediaSizing>()
        koin.get<MediaLoader>()
        koin.get<SettingsStore>()
        koin.get<SystemActions>()
        koin.get<Haptics>()
        // WP-9.1: the two typed views of the library are one PhotoKit bridge — the
        // graph hands the same PhotoKitMediaLibrary out read-side and write-side.
        assertSame<Any>(koin.get<MediaLibrary>(), koin.get<MediaDeleter>())
        assertSame<Any>(koin.get<PhotoKitMediaLibrary>(), koin.get<MediaDeleter>())
        koin.get<BuildDeckUseCase>()
        koin.get<RecordDecisionUseCase>()
        koin.get<UndoDecisionUseCase>()
        // WP-3.1: the review-queue trio. CommitDeletionsUseCase resolving through the
        // real graph is the NFR-5 wiring check — it is the only MediaDeleter consumer,
        // so a missing binding would break the delete path at runtime.
        koin.get<CommitDeletionsUseCase>()
        koin.get<PruneQueueUseCase>()
        koin.get<RestoreFromQueueUseCase>()
        // WP-4.1: the VideoPlayer actual and the deck preloader share the one cache.
        koin.get<VideoItemCache>()
        koin.get<VideoPreloader>()
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

    /**
     * The graph half of the WP-9.1 compile-time guarantee: **no ViewModel is wired to
     * a [MediaDeleter]** — every one of them reads through `MediaLibrary`, which has
     * no delete. A counting stand-in replaces the real binding (later Koin modules
     * win), and every ViewModel resolves through the production graph: only the
     * Queue's pull of `CommitDeletionsUseCase` — the one consumer — may reach the
     * write door, never a ViewModel itself. A ViewModel constructor that grew a
     * `MediaDeleter` parameter would push the count past these ceilings even if the
     * `scripts/check-rules.sh` allowlist were edited to let the type through.
     */
    @OptIn(ExperimentalTestApi::class)
    @Suppress("DEPRECATION") // v1 runComposeUiTest, consistent with the rest of the suite.
    @Test
    fun `NFR-5 no view model resolves MediaDeleter only CommitDeletionsUseCase does`() {
        val counting = CountingMediaDeleter()
        startKoin { modules(commonModule, iosModule, nfr5GuardModule(counting)) }
        val koin = KoinPlatform.getKoin()
        runComposeUiTest {
            // Every ViewModel the app can open, with its route parameters. Resolution
            // runs inside runComposeUiTest so the paused virtual clock keeps each
            // ViewModel's init coroutines suspended — nothing reaches PhotoKit or the
            // live SQLite driver.
            //
            // QueueViewModel last: it is the only ViewModel that touches the write
            // door at all, and only transitively, through its CommitDeletionsUseCase
            // dependency (the sole consumer). The six before it must add nothing.
            koin.get<PermissionViewModel>()
            koin.get<HomeViewModel>()
            koin.get<AlbumsViewModel> { parametersOf(MediaTypeFilter.ALL) }
            koin.get<MonthsViewModel> { parametersOf(MediaTypeFilter.ALL) }
            koin.get<SettingsViewModel>()
            koin.get<SwipeViewModel> { parametersOf(DeckSource.All, MediaTypeFilter.ALL) }
            assertEquals(0, counting.resolutions)
            koin.get<QueueViewModel>()
            assertEquals(1, counting.resolutions)
        }
        // The delete path itself resolves, and the singleton means no second pull.
        koin.get<CommitDeletionsUseCase>()
        assertEquals(1, counting.resolutions)
    }

    /**
     * The FR-38 half of the same graph guarantee: **SwipeViewModel is the only
     * consumer of [MediaHider]**. A counting stand-in replaces the real binding
     * (later Koin modules win) and every ViewModel resolves through the
     * production graph — the five readers and the Queue add nothing, the deck's
     * ViewModel pulls the hide door exactly once. A ViewModel constructor that
     * grew a `MediaHider` parameter would trip this even if the
     * `scripts/check-rules.sh` allowlist were edited to let the type through.
     */
    @OptIn(ExperimentalTestApi::class)
    @Suppress("DEPRECATION") // v1 runComposeUiTest, consistent with the rest of the suite.
    @Test
    fun `FR-38 only the swipe view model resolves MediaHider`() {
        val counting = CountingMediaHider()
        startKoin { modules(commonModule, iosModule, fr38GuardModule(counting)) }
        val koin = KoinPlatform.getKoin()
        runComposeUiTest {
            koin.get<PermissionViewModel>()
            koin.get<HomeViewModel>()
            koin.get<AlbumsViewModel> { parametersOf(MediaTypeFilter.ALL) }
            koin.get<MonthsViewModel> { parametersOf(MediaTypeFilter.ALL) }
            koin.get<SettingsViewModel>()
            koin.get<QueueViewModel>()
            assertEquals(0, counting.resolutions)
            koin.get<SwipeViewModel> { parametersOf(DeckSource.All, MediaTypeFilter.ALL) }
            assertEquals(1, counting.resolutions)
        }
        // The singleton means resolving the door again does not count twice.
        koin.get<MediaHider>()
        assertEquals(1, counting.resolutions)
    }
}

/** Minimal owner so the definition under test builds outside any screen. */
private val ThrowawayStoreOwner =
    object : androidx.lifecycle.ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

/**
 * A [MediaDeleter] that counts how often the graph resolves it (WP-9.1). Bound over
 * the real `MediaDeleter` definition by [nfr5GuardModule]; nothing in the graph may
 * touch its [delete].
 */
private class CountingMediaDeleter : MediaDeleter {
    var resolutions = 0

    override suspend fun delete(ids: List<String>): DeleteResult = DeleteResult.Cancelled
}

/**
 * The app graph with [MediaDeleter] swapped for a counting stand-in. Later-loaded
 * Koin modules win, so this replaces `iosModule`'s PhotoKit binding for the test.
 */
private fun nfr5GuardModule(counting: CountingMediaDeleter): Module =
    module {
        single<MediaDeleter> {
            counting.resolutions++
            counting
        }
    }

/**
 * A [MediaHider] that counts how often the graph resolves it (FR-38). Bound over
 * the real binding by [fr38GuardModule]; its [setHidden] never applies anything —
 * resolving is all this guard measures.
 */
private class CountingMediaHider : MediaHider {
    var resolutions = 0

    override suspend fun setHidden(
        id: String,
        hidden: Boolean,
    ): HideResult = HideResult.FAILED
}

/**
 * The app graph with [MediaHider] swapped for a counting stand-in. Later-loaded
 * Koin modules win, so this replaces `iosModule`'s PhotoKit binding for the test.
 */
private fun fr38GuardModule(counting: CountingMediaHider): Module =
    module {
        single<MediaHider> {
            counting.resolutions++
            counting
        }
    }
