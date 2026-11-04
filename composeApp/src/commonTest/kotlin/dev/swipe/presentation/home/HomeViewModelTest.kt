@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.home

import app.cash.turbine.test
import dev.swipe.domain.model.AlbumSource
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.model.Stats
import dev.swipe.domain.usecase.LoadSourcesUseCase
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home's state owner (§6.5, FR-2, FR-4, FR-5, FR-7, FR-9, FR-10, NFR-4):
 * progressive counts merged into one state, one refresh per `changes()` emission,
 * the filter reload, navigation events, and the promise that the Months path is
 * never touched from Home (Months is FR-7 and belongs to WP-5.3).
 */
class HomeViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-10 home shows lifetime stats and queue size`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(4))
            val store =
                seedStore(
                    "asset-1" to Decision.KEEP,
                    "asset-2" to Decision.DELETE,
                    "asset-3" to Decision.DELETE,
                    "asset-4" to Decision.DELETED,
                )
            val viewModel = homeViewModel(library, store)

            viewModel.uiState.test {
                var state = awaitItem()
                while (state.stats == null || state.access == null) state = awaitItem()
                assertEquals(Stats(reviewed = 4, kept = 1, deleted = 1, queued = 2), state.stats)
                assertEquals(2, state.queueCount)
                assertEquals(LibraryAccess.FULL, state.access) // the FR-2 banner signal

                // A fresh swipe-to-delete grows the badge without a reload (FR-10).
                store.record("asset-9", Decision.DELETE, "s")
                var updated = awaitItem()
                while (updated.queueCount != 3) updated = awaitItem()
                assertEquals(3, updated.stats?.queued)
            }
        }

    @Test
    fun `FR-5 changing type filter reloads counts`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photoItems(3) + videoItems(2),
                    albums = mapOf("a1" to setOf("asset-1", "video-1", "video-2")),
                )
            val viewModel = homeViewModel(library, FakeDecisionStore())
            val all = viewModel.awaitSettled(this)
            assertEquals(5, all.shuffleCount)
            assertEquals(3, all.albumRows.single().count)

            viewModel.onIntent(HomeIntent.SetTypeFilter(MediaTypeFilter.VIDEOS))

            // Observe emissions, not just the settled end state: the switch drops
            // the ALL numbers to placeholders before any VIDEOS count lands —
            // album rows included — so stale numbers never sit under the new
            // label (FR-5).
            var sawCleared = false
            viewModel.uiState.test {
                var s = awaitItem()
                while (!sawCleared || s.shuffleCount == null) {
                    if (s.typeFilter == MediaTypeFilter.VIDEOS && !sawCleared) {
                        sawCleared = true
                        assertNull(s.shuffleCount)
                        assertTrue(s.smartRows.all { it.count == null })
                        assertTrue(s.albumRows.all { it.count == null })
                    }
                    s = awaitItem()
                }
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(sawCleared, "the switch never cleared the counts")

            val videos = viewModel.awaitSettled(this)
            assertEquals(MediaTypeFilter.VIDEOS, videos.typeFilter)
            assertEquals(2, videos.shuffleCount) // only the videos (FR-5)
            assertEquals(2, videos.albumRows.single().count) // a1 minus the photo
            // The reload really re-ran every fetch under the new filter.
            assertTrue(library.albumsCalls >= 2)
            assertTrue(library.assetIdsCalls.all { it.second == MediaTypeFilter.ALL || it.second == MediaTypeFilter.VIDEOS })
            assertTrue(library.assetIdsCalls.any { it.second == MediaTypeFilter.VIDEOS })
        }

    @Test
    fun `FR-9 source counts exclude decided items`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(4))
            val store = seedStore("asset-1" to Decision.KEEP, "asset-2" to Decision.DELETE)
            val viewModel = homeViewModel(library, store)

            val settled = viewModel.awaitSettled(this)
            assertEquals(2, settled.shuffleCount) // 4 items minus the two decided
            assertTrue(settled.smartRows.all { it.count == 0 })
        }

    @Test
    fun `NFR-4 home state is emitted before counts finish loading`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(3))
            library.gateNextSmartCounts = true // the full-library smart pass hangs
            val viewModel = homeViewModel(library, FakeDecisionStore())

            // Home mounts immediately: five placeholder rows, no numbers anywhere.
            assertEquals(HomeUiState(), viewModel.uiState.value)

            // The cheap rows land while the smart pass is still in flight (§6.5):
            // the screen has real Shuffle numbers to show already.
            testScheduler.advanceUntilIdle()
            val partial = viewModel.uiState.value
            assertEquals(3, partial.shuffleCount)
            assertTrue(partial.smartRows.all { it.count == null })
            assertEquals(1, library.smartCountsCalls) // one pass, still suspended

            library.releaseSmartCounts()
            val settled = viewModel.awaitSettled(this)
            assertTrue(settled.smartRows.all { it.count != null })
        }

    @Test
    fun `FR-4 one changes emission refreshes counts exactly once`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(3))
            val viewModel = homeViewModel(library, FakeDecisionStore())
            viewModel.awaitSettled(this)

            val albumsBefore = library.albumsCalls
            val smartBefore = library.smartCountsCalls
            library.notifyChange()
            testScheduler.advanceUntilIdle()

            // One refresh — not one per collector.
            assertEquals(albumsBefore + 1, library.albumsCalls)
            assertEquals(smartBefore + 1, library.smartCountsCalls)
        }

    @Test
    fun `NFR-4 home never loads months`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(3))
            val viewModel = homeViewModel(library, FakeDecisionStore())
            viewModel.awaitSettled(this)
            library.notifyChange()
            testScheduler.advanceUntilIdle()
            viewModel.onIntent(HomeIntent.SetTypeFilter(MediaTypeFilter.PHOTOS))
            testScheduler.advanceUntilIdle()

            // Startup, a changes() refresh, and a filter reload: the By-month
            // screen is FR-7 (WP-5.3); Home never pays for its full-library path.
            assertEquals(0, library.monthsCalls)
        }

    @Test
    fun `FR-9 a changes refresh never flickers refined counts back to estimates`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(4))
            val store = seedStore("asset-1" to Decision.KEEP, "asset-2" to Decision.DELETE)
            val viewModel = homeViewModel(library, store)
            val settled = viewModel.awaitSettled(this)
            assertEquals(2, settled.shuffleCount) // FR-9-exact, not the raw 4

            // The refresh re-runs the ladder: its raw Shuffle estimate (4) arrives
            // before its own refinement, but the exactness mask keeps the refined
            // number on screen (§6.5) — no exact → estimate → exact flicker.
            library.gateNextSmartCounts = true
            library.notifyChange()
            testScheduler.advanceUntilIdle()
            assertEquals(2, library.smartCountsCalls) // the re-run is in flight, gated
            assertEquals(2, viewModel.uiState.value.shuffleCount)

            library.releaseSmartCounts()
            val refreshed = viewModel.awaitSettled(this)
            assertEquals(2, refreshed.shuffleCount)
        }

    @Test
    fun `FR-7 opening a row navigates with the selected type filter`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = homeViewModel(FakeMediaLibrary(photoItems(2)), FakeDecisionStore())

            viewModel.events.test {
                viewModel.onIntent(HomeIntent.OpenSource.Shuffle)
                assertEquals(HomeEvent.OpenDeck(DeckSource.All, MediaTypeFilter.ALL), awaitItem())

                viewModel.onIntent(HomeIntent.SetTypeFilter(MediaTypeFilter.PHOTOS))
                viewModel.onIntent(HomeIntent.OpenSource.Smart(SmartFilter.SCREENSHOTS))
                assertEquals(
                    HomeEvent.OpenDeck(DeckSource.Smart(SmartFilter.SCREENSHOTS), MediaTypeFilter.PHOTOS),
                    awaitItem(),
                )

                viewModel.onIntent(HomeIntent.OpenSource.Album(AlbumSource("a1", "Trip", 3, "asset-1")))
                assertEquals(
                    HomeEvent.OpenDeck(DeckSource.Album("a1", "Trip"), MediaTypeFilter.PHOTOS),
                    awaitItem(),
                )

                viewModel.onIntent(HomeIntent.OpenQueue)
                assertEquals(HomeEvent.OpenQueue, awaitItem())

                viewModel.onIntent(HomeIntent.OpenSettings)
                assertEquals(HomeEvent.OpenSettings, awaitItem())
            }
        }

    // ---- helpers ----

    private fun homeViewModel(
        library: FakeMediaLibrary,
        store: FakeDecisionStore,
    ): HomeViewModel = HomeViewModel(library, store, LoadSourcesUseCase(library, store))

    /** Drive the scheduler until every row's count has arrived (ungated fixtures). */
    private suspend fun HomeViewModel.awaitSettled(scope: TestScope): HomeUiState {
        var state = uiState.value
        var spins = 0
        while (state.shuffleCount == null || state.smartRows.any { it.count == null }) {
            scope.testScheduler.advanceUntilIdle()
            state = uiState.value
            check(++spins < 100) { "counts never settled: $state" }
        }
        return state
    }

    private suspend fun seedStore(vararg decisions: Pair<String, Decision>): FakeDecisionStore =
        FakeDecisionStore().also { store ->
            decisions.forEach { (id, decision) -> store.record(id, decision, "seed") }
        }

    private fun photoItems(count: Int): List<MediaItem> = (1..count).map { item("asset-$it", MediaType.PHOTO) }

    private fun videoItems(count: Int): List<MediaItem> = (1..count).map { item("video-$it", MediaType.VIDEO) }

    private fun item(
        id: String,
        type: MediaType,
    ): MediaItem =
        MediaItem(
            id = id,
            type = type,
            createdAt = null,
            durationMs = if (type == MediaType.VIDEO) 5_000L else null,
            widthPx = 120,
            heightPx = 160,
            isFavorite = false,
            isScreenshot = false,
            burstId = null,
            isCloudOnly = null,
        )
}
