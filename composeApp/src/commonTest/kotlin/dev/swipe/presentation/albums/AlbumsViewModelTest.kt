@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.albums

import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.presentation.home.HomeAlbumRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Albums screen's state owner (WP-5.3, FR-8, FR-9, FR-5, §6.5 *Home
 * counts*): rows arriving with the cheap estimate and converging to the exact
 * FR-9 count, the `changes()` reload, the failed fetch keeping the rows, and
 * the deck event carrying the screen's fixed filter.
 */
class AlbumsViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-9 album rows arrive with the estimate and converge to the exact count`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = FakeDecisionStore()
            store.record("asset-2", Decision.KEEP, "seed")
            val library =
                FakeMediaLibrary(
                    photos(3),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2", "asset-3")),
                )
            library.nextAlbumsEstimate = 99 // PhotoKit's lagging estimate first

            AlbumsViewModel(MediaTypeFilter.ALL, library, store).uiState.test {
                var state = awaitItem()
                while (state.rows.isEmpty()) state = awaitItem()
                assertEquals(99, state.rows.single().count) // the estimate lands first

                state = awaitItem()
                assertEquals(2, state.rows.single().count) // 3 members − 1 decided (FR-9)
                ensureAllEventsConsumed()
            }
        }

    @Test
    fun `FR-8 opening an album navigates with the screen's filter`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = AlbumsViewModel(MediaTypeFilter.VIDEOS, FakeMediaLibrary(emptyList()), FakeDecisionStore())

            viewModel.events.test {
                val row = HomeAlbumRow("a1", "Trip", "asset-1", 3)
                viewModel.onIntent(AlbumsIntent.OpenAlbum(row))
                assertEquals(
                    AlbumsEvent.OpenDeck(DeckSource.Album("a1", "Trip"), MediaTypeFilter.VIDEOS),
                    awaitItem(),
                )
            }
        }

    @Test
    fun `FR-8 a library change reloads the rows`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photos(2),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2")),
                )
            val viewModel = AlbumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore())
            testScheduler.advanceUntilIdle()
            assertEquals(1, library.albumsCalls)

            library.notifyChange()
            testScheduler.advanceUntilIdle()
            assertEquals(2, library.albumsCalls)
            assertEquals(
                2,
                viewModel.uiState.value.rows
                    .single()
                    .count,
            )
        }

    @Test
    fun `6-8 a failed albums fetch keeps the rows on screen`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photos(2),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2")),
                )
            val viewModel = AlbumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore())
            testScheduler.advanceUntilIdle()
            val settled = viewModel.uiState.value.rows

            library.failNextAlbums = IllegalStateException("PhotoKit said no")
            library.notifyChange()
            testScheduler.advanceUntilIdle()
            // The screen keeps what it showed; the next change retries (§6.8).
            assertEquals(settled, viewModel.uiState.value.rows)
            assertTrue(
                viewModel.uiState.value.rows
                    .isNotEmpty(),
            )
        }

    // ---- helpers ----

    private fun photos(count: Int): List<MediaItem> =
        (1..count).map { i ->
            MediaItem(
                id = "asset-$i",
                type = MediaType.PHOTO,
                createdAt = null,
                durationMs = null,
                widthPx = 120,
                heightPx = 160,
                isFavorite = false,
                isScreenshot = false,
                burstId = null,
                isCloudOnly = null,
            )
        }
}
