@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.home

import app.cash.turbine.test
import dev.swipe.data.MediaLibrary
import dev.swipe.data.ShuffleExclusionStore
import dev.swipe.domain.model.AlbumSource
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.model.Stats
import dev.swipe.domain.usecase.DeckMembership
import dev.swipe.domain.usecase.SourceCountsUseCase
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeShuffleExclusionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    fun `FR-37 toggling an exclusion refreshes the Shuffle count`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photoItems(4),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2")),
                )
            val exclusions = FakeShuffleExclusionStore()
            val viewModel =
                homeViewModel(library, FakeDecisionStore(), exclusions)
            val settled = viewModel.awaitSettled(this)
            assertEquals(4, settled.shuffleCount)

            // Toggling an album off Shuffle (from the Albums screen) refreshes the
            // Home count: 4 minus the two members of a1 (FR-9, FR-37).
            exclusions.setExcluded("a1", true)
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.shuffleCount)

            exclusions.setExcluded("a1", false)
            testScheduler.advanceUntilIdle()
            assertEquals(4, viewModel.uiState.value.shuffleCount)
        }

    @Test
    fun `NFR-4 home state is emitted before counts finish loading`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                GatingLibrary(
                    FakeMediaLibrary(photoItems(3), albums = mapOf("a1" to setOf("asset-1"))),
                    // hold every count read: Shuffle, the smart rows, the album
                    // counts all hang while the cheap identity lands.
                    MediaTypeFilter.ALL,
                )
            val viewModel = homeViewModel(library, FakeDecisionStore())

            // Home mounts immediately: five placeholder rows, no numbers anywhere.
            assertEquals(HomeUiState(), viewModel.uiState.value)

            // The album identity (covers, no enumeration) lands while every count
            // is still in flight (§6.5): the screen has rows to show already.
            testScheduler.advanceUntilIdle()
            val partial = viewModel.uiState.value
            assertNull(partial.shuffleCount)
            assertTrue(partial.smartRows.all { it.count == null })
            assertEquals(listOf("a1"), partial.albumRows.map { it.id })
            assertTrue(partial.albumRows.all { it.count == null })

            library.release()
            val settled = viewModel.awaitSettled(this)
            assertEquals(3, settled.shuffleCount)
            assertTrue(settled.smartRows.all { it.count != null })
            assertEquals(1, settled.albumRows.single().count)
        }

    @Test
    fun `FR-4 one changes emission refreshes counts exactly once`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(3))
            val viewModel = homeViewModel(library, FakeDecisionStore())
            viewModel.awaitSettled(this)

            val albumsBefore = library.albumsCalls
            val shuffleBefore = library.assetIdsCalls.count { it.first == DeckSource.All }
            library.notifyChange()
            testScheduler.advanceUntilIdle()

            // One refresh — not one per collector.
            assertEquals(albumsBefore + 1, library.albumsCalls)
            assertEquals(shuffleBefore + 1, library.assetIdsCalls.count { it.first == DeckSource.All })
            // The raw smart-count pass is gone: Home counts run only on the deck path.
            assertEquals(0, library.smartCountsCalls)
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
    fun `FR-9 a changes refresh never flickers settled counts`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = GatingLibrary(FakeMediaLibrary(photoItems(4)))
            val store = seedStore("asset-1" to Decision.KEEP, "asset-2" to Decision.DELETE)
            val viewModel = homeViewModel(library, store)
            val settled = viewModel.awaitSettled(this)
            assertEquals(2, settled.shuffleCount) // FR-9-exact, not the raw 4

            // The refresh re-runs every count; while its reads are in flight the
            // settled numbers stay on screen — the re-run carries no estimates, so
            // there is nothing to flicker to: never the placeholder, never the
            // raw 4 (§6.5).
            library.gate(MediaTypeFilter.ALL)
            library.notifyChange()
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.shuffleCount)
            assertTrue(
                viewModel.uiState.value.smartRows
                    .all { it.count == 0 },
            )

            library.release()
            val refreshed = viewModel.awaitSettled(this)
            assertEquals(2, refreshed.shuffleCount)
        }

    @Test
    fun `FR-30 a reset raises the Shuffle count on the next resume without a library change`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(5))
            val store =
                seedStore(
                    "asset-1" to Decision.KEEP,
                    "asset-2" to Decision.KEEP,
                    "asset-3" to Decision.DELETE,
                )
            val viewModel = homeViewModel(library, store)
            val settled = viewModel.awaitSettled(this)
            assertEquals(2, settled.shuffleCount) // 5 − the two KEEPs and the queued DELETE

            // The first resume only records the decided set — the startup load
            // already counted against it (FR-30, §6.5).
            viewModel.onIntent(HomeIntent.Resumed)
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.shuffleCount)

            // "Reset all decisions" clears the KEEPs; the queue stays (FR-30).
            store.clearKeeps()
            viewModel.onIntent(HomeIntent.Resumed)
            testScheduler.advanceUntilIdle()

            // No library change happened, so only the resume can re-run the
            // counts: 5 − the one still-queued DELETE. Without the fix this
            // stays 2 — that was the FR-30 bug.
            assertEquals(4, viewModel.uiState.value.shuffleCount)
            // The queued DELETE survives the reset and stays out of the count (AC3).
            assertEquals(listOf("asset-3"), store.deleteQueue().first())
        }

    @Test
    fun `FR-30 a resume with no decision change does not re-run the counts`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(photoItems(3))
            val store = seedStore("asset-1" to Decision.KEEP)
            val viewModel = homeViewModel(library, store)
            viewModel.awaitSettled(this)

            viewModel.onIntent(HomeIntent.Resumed) // records the decided set
            testScheduler.advanceUntilIdle()
            viewModel.onIntent(HomeIntent.Resumed) // no change → no reload
            testScheduler.advanceUntilIdle()

            val before = library.assetIdsCalls.count { it.first == DeckSource.All }
            viewModel.onIntent(HomeIntent.Resumed) // still no change
            testScheduler.advanceUntilIdle()
            // At most one re-run per change: a no-change resume fetches nothing (§6.5).
            assertEquals(before, library.assetIdsCalls.count { it.first == DeckSource.All })
        }

    @Test
    fun `FR-30 a resume re-run never dips a settled count to the placeholder`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = GatingLibrary(FakeMediaLibrary(photoItems(4)))
            val store = seedStore("asset-1" to Decision.KEEP, "asset-2" to Decision.DELETE)
            val viewModel = homeViewModel(library, store)
            val settled = viewModel.awaitSettled(this)
            assertEquals(2, settled.shuffleCount)

            viewModel.onIntent(HomeIntent.Resumed) // records the decided set
            testScheduler.advanceUntilIdle()

            // A reset then resume re-runs the counts; while the reads are in
            // flight the settled number stays on screen — the re-run carries
            // only final numbers, so never the placeholder (§6.5).
            store.clearKeeps()
            library.gate(MediaTypeFilter.ALL)
            viewModel.onIntent(HomeIntent.Resumed)
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.shuffleCount)

            // Release and run the held reads to completion; `awaitSettled` would
            // return the held 2 immediately (nothing ever went back to null —
            // that is the point), so advance and read the settled value.
            library.release()
            testScheduler.advanceUntilIdle()
            assertEquals(3, viewModel.uiState.value.shuffleCount) // 4 − the one queued DELETE
        }

    @Test
    fun `FR-5 a type switch shows only placeholder-then-final counts`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photoItems(3) + videoItems(2),
                    albums = mapOf("a1" to setOf("asset-1", "video-1", "video-2")),
                )
            val store = seedStore("video-1" to Decision.KEEP)
            val viewModel = homeViewModel(library, store)
            val all = viewModel.awaitSettled(this)
            assertEquals(4, all.shuffleCount) // 5 items − the decided video
            assertEquals(2, all.albumRows.single().count) // a1 − the decided video

            viewModel.onIntent(HomeIntent.SetTypeFilter(MediaTypeFilter.VIDEOS))

            // Every number the VIDEOS phase shows is the placeholder or the final
            // count: neither the old ALL numbers nor the raw fetch sizes (2 videos
            // before the subtraction) ever appear — that jump was the flicker.
            val seenShuffle = mutableListOf<Int?>()
            val seenAlbum = mutableListOf<Int?>()
            var settledVideos = false
            viewModel.uiState.test {
                var s = awaitItem()
                while (!settledVideos) {
                    if (s.typeFilter == MediaTypeFilter.VIDEOS) {
                        seenShuffle += s.shuffleCount
                        seenAlbum += s.albumRows.singleOrNull()?.count
                        settledVideos =
                            s.shuffleCount == 1 &&
                            s.albumRows.singleOrNull()?.count == 1 &&
                            s.smartRows.none { it.count == null }
                    }
                    if (!settledVideos) s = awaitItem()
                }
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(seenShuffle.isNotEmpty(), "no VIDEOS-phase state was observed")
            assertNull(seenShuffle.first(), "the switch did not clear the Shuffle count first")
            assertTrue(seenShuffle.all { it == null || it == 1 }, "Shuffle flickered: $seenShuffle")
            assertTrue(seenAlbum.all { it == null || it == 1 }, "album row flickered: $seenAlbum")
        }

    @Test
    fun `FR-5 a late result from the superseded filter never overwrites the new counts`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                GatingLibrary(
                    FakeMediaLibrary(photoItems(3) + videoItems(2)),
                    MediaTypeFilter.ALL, // the ALL run hangs on its first read
                )
            val viewModel = homeViewModel(library, FakeDecisionStore())
            testScheduler.advanceUntilIdle()
            assertNull(viewModel.uiState.value.shuffleCount) // the ALL run is in flight

            // The switch does not wait for the slow ALL run: VIDEOS loads and settles.
            viewModel.onIntent(HomeIntent.SetTypeFilter(MediaTypeFilter.VIDEOS))
            val videos = viewModel.awaitSettled(this)
            assertEquals(2, videos.shuffleCount) // only the videos (FR-5)

            // The hanging ALL enumeration finally answers — a superseded filter's
            // numbers can never land on the new label.
            library.release()
            testScheduler.advanceUntilIdle()
            assertEquals(videos, viewModel.uiState.value)
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
        library: MediaLibrary,
        store: FakeDecisionStore,
        exclusions: ShuffleExclusionStore = FakeShuffleExclusionStore(),
    ): HomeViewModel =
        HomeViewModel(
            library,
            store,
            SourceCountsUseCase(library, store, DeckMembership(library, exclusions)),
            exclusions,
        )

    /**
     * Wraps a [FakeMediaLibrary] holding every `assetIds` read whose type matches
     * [heldType] on a deferred until [release] — the seam that keeps one filter's
     * load in flight across a type switch (FR-5), and one Shuffle read in flight
     * across a `changes()` refresh (§6.5).
     */
    private class GatingLibrary(
        private val delegate: FakeMediaLibrary,
        vararg heldTypes: MediaTypeFilter,
    ) : MediaLibrary by delegate {
        private val held = heldTypes.toMutableSet()
        private var gate: CompletableDeferred<Unit>? = null

        /** Start holding every `assetIds` read of these types (in addition). */
        fun gate(vararg types: MediaTypeFilter) {
            held += types
        }

        override suspend fun assetIds(
            source: DeckSource,
            type: MediaTypeFilter,
        ): List<String> {
            if (type in held) {
                val g = gate ?: CompletableDeferred<Unit>().also { gate = it }
                g.await()
            }
            return delegate.assetIds(source, type)
        }

        /** Let every held read answer, and stop holding. */
        fun release() {
            held.clear()
            gate?.complete(Unit)
            gate = null
        }

        /** Fire the inner fake's `changes()` seam (FR-7 refresh). */
        fun notifyChange() = delegate.notifyChange()
    }

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
