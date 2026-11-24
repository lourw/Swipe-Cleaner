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
import dev.swipe.fakes.FakeShuffleExclusionStore
import dev.swipe.fakes.albumsViewModel
import dev.swipe.presentation.ListStatus
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Albums screen's state owner (WP-5.3, FR-8, FR-9, FR-5, FR-37, §6.5 *Home
 * counts*): rows arriving with the placeholder and settling at the exact FR-9
 * count (WP-8.12 — the estimate is never displayed), the `changes()` reload,
 * the failed fetch keeping the rows, the deck event carrying the screen's
 * fixed filter, and the FR-37 exclude-from-Shuffle switches.
 */
class AlbumsViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-9 album rows show no estimate only the exact count`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = FakeDecisionStore()
            store.record("asset-2", Decision.KEEP, "seed")
            val library =
                FakeMediaLibrary(
                    photos(3),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2", "asset-3")),
                )
            library.nextAlbumsEstimate = 99 // PhotoKit's lagging estimate — never displayed (WP-8.12)

            albumsViewModel(MediaTypeFilter.ALL, library, store).uiState.test {
                var state = awaitItem()
                while (state.rows.isEmpty()) state = awaitItem()
                // Rows arrive with the placeholder, not the estimate (NFR-4).
                assertEquals(null, state.rows.single().count)

                state = awaitItem()
                assertEquals(2, state.rows.single().count) // 3 members − 1 decided (FR-9)
                ensureAllEventsConsumed()
            }
        }

    @Test
    fun `FR-8 opening an album navigates with the screen's filter`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = albumsViewModel(MediaTypeFilter.VIDEOS, FakeMediaLibrary(emptyList()), FakeDecisionStore())

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
            val viewModel = albumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore())
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
            val viewModel = albumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore())
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

    @Test
    fun `FR-8 the screen opens in the loading state until the scan answers`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photos(2),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2")),
                )

            val viewModel = albumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore())
            // WP-9.6: the first state says "scanning", not "empty".
            assertEquals(ListStatus.Loading, viewModel.uiState.value.status)
            assertTrue(
                viewModel.uiState.value.rows
                    .isEmpty(),
            )

            testScheduler.advanceUntilIdle()
            assertEquals(ListStatus.Ready, viewModel.uiState.value.status)
            assertTrue(
                viewModel.uiState.value.rows
                    .isNotEmpty(),
            )
        }

    @Test
    fun `FR-8 an empty scan answers with the empty state`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel =
                albumsViewModel(MediaTypeFilter.ALL, FakeMediaLibrary(emptyList()), FakeDecisionStore())
            testScheduler.advanceUntilIdle()

            assertEquals(ListStatus.Empty, viewModel.uiState.value.status)
        }

    @Test
    fun `FR-8 a scan that fails with nothing on screen explains itself and Retry recovers`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photos(2),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2")),
                )
            library.failNextAlbums = IllegalStateException("PhotoKit said no")

            val viewModel = albumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore())
            testScheduler.advanceUntilIdle()
            // WP-9.6 (FR-8): the failed scan is no longer a silent empty screen.
            assertEquals(ListStatus.Error, viewModel.uiState.value.status)
            assertTrue(
                viewModel.uiState.value.rows
                    .isEmpty(),
            )

            viewModel.onIntent(AlbumsIntent.Retry)
            testScheduler.advanceUntilIdle()
            assertEquals(ListStatus.Ready, viewModel.uiState.value.status)
            assertEquals(1, viewModel.uiState.value.rows.size)
        }

    @Test
    fun `FR-8 a library change retries the failed scan`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photos(2),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2")),
                )
            library.failNextAlbums = IllegalStateException("PhotoKit said no")

            val viewModel = albumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore())
            testScheduler.advanceUntilIdle()
            assertEquals(ListStatus.Error, viewModel.uiState.value.status)

            library.notifyChange()
            testScheduler.advanceUntilIdle()
            assertEquals(ListStatus.Ready, viewModel.uiState.value.status)
        }

    @Test
    fun `FR-37 toggling a row excludes the album and marks the switch`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photos(3),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2", "asset-3")),
                )
            val exclusions = FakeShuffleExclusionStore()
            val viewModel = albumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore(), exclusions)
            testScheduler.advanceUntilIdle()
            assertEquals(emptySet(), viewModel.uiState.value.excludedAlbumIds)

            val row =
                viewModel.uiState.value.rows
                    .single()
            viewModel.onIntent(AlbumsIntent.ToggleShuffleExclusion(row))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("a1" to true), exclusions.setExcludedCalls)
            assertEquals(setOf("a1"), viewModel.uiState.value.excludedAlbumIds)

            // Toggling again includes it (FR-37).
            viewModel.onIntent(AlbumsIntent.ToggleShuffleExclusion(row))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("a1" to true, "a1" to false), exclusions.setExcludedCalls)
            assertEquals(emptySet(), viewModel.uiState.value.excludedAlbumIds)
        }

    @Test
    fun `FR-37 a failed exclusion write leaves the switch where it was`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photos(2),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2")),
                )
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("a1"))
            val viewModel = albumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore(), exclusions)
            testScheduler.advanceUntilIdle()
            assertEquals(setOf("a1"), viewModel.uiState.value.excludedAlbumIds)

            exclusions.failNextSetExcluded = IllegalStateException("write failed")
            viewModel.onIntent(
                AlbumsIntent.ToggleShuffleExclusion(
                    viewModel.uiState.value.rows
                        .single(),
                ),
            )
            testScheduler.advanceUntilIdle()

            // The switch state comes back from the store, so the failed write
            // changed nothing on screen (NFR-7).
            assertEquals(setOf("a1"), viewModel.uiState.value.excludedAlbumIds)
            assertFalse(exclusions.setExcludedCalls.any())
        }

    @Test
    fun `FR-37 exclusions never change the row counts`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    photos(3),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2", "asset-3")),
                )
            // The album is excluded, yet opening it still shows its full deck,
            // so its row keeps the full FR-9 count.
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("a1"))
            val viewModel = albumsViewModel(MediaTypeFilter.ALL, library, FakeDecisionStore(), exclusions)
            testScheduler.advanceUntilIdle()

            assertEquals(
                3,
                viewModel.uiState.value.rows
                    .single()
                    .count,
            )
            assertEquals(setOf("a1"), viewModel.uiState.value.excludedAlbumIds)
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
