@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.months

import app.cash.turbine.test
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket
import dev.swipe.fakes.FakeMediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The By month screen's state owner (WP-5.3, FR-7, FR-5, NFR-4): the scan
 * runs exactly once when the screen opens, buckets come back newest first,
 * the deck event carries the screen's fixed filter, and a failed scan keeps
 * what the screen shows until the next `changes()` retry.
 */
class MonthsViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-7 opening the screen scans once and shows buckets newest first`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    listOf(
                        photo("p-1", utc(2026, 3, 15)),
                        photo("p-2", utc(2026, 3, 2)),
                        photo("p-3", utc(2025, 12, 20)),
                        photo("p-4", utc(2025, 5, 10)),
                    ),
                )

            val viewModel = MonthsViewModel(MediaTypeFilter.ALL, library)
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    MonthBucket(2026, 3, 2),
                    MonthBucket(2025, 12, 1),
                    MonthBucket(2025, 5, 1),
                ),
                viewModel.uiState.value.buckets,
            )
            // One scan for the open (NFR-4: never at Home startup, once here).
            assertEquals(1, library.monthsCalls)
        }

    @Test
    fun `FR-5 the scan applies the screen's type filter`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library =
                FakeMediaLibrary(
                    listOf(
                        photo("p-1", utc(2026, 3, 15)),
                        video("v-1", utc(2026, 3, 16)),
                        video("v-2", utc(2025, 12, 20)),
                    ),
                )

            val viewModel = MonthsViewModel(MediaTypeFilter.VIDEOS, library)
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(MonthBucket(2026, 3, 1), MonthBucket(2025, 12, 1)),
                viewModel.uiState.value.buckets,
            )
        }

    @Test
    fun `FR-7 opening a month navigates with the screen's filter`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = MonthsViewModel(MediaTypeFilter.PHOTOS, FakeMediaLibrary(emptyList()))

            viewModel.events.test {
                viewModel.onIntent(MonthsIntent.OpenMonth(MonthBucket(2026, 3, 2)))
                assertEquals(
                    MonthsEvent.OpenDeck(DeckSource.Month(2026, 3), MediaTypeFilter.PHOTOS),
                    awaitItem(),
                )
            }
        }

    @Test
    fun `6-8 a failed scan keeps the buckets and the next change retries`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val library = FakeMediaLibrary(listOf(photo("p-1", utc(2026, 3, 15))))
            val viewModel = MonthsViewModel(MediaTypeFilter.ALL, library)
            testScheduler.advanceUntilIdle()
            val settled = viewModel.uiState.value.buckets

            library.failNextMonths = IllegalStateException("PhotoKit said no")
            library.notifyChange()
            testScheduler.advanceUntilIdle()
            assertEquals(settled, viewModel.uiState.value.buckets)
            assertEquals(2, library.monthsCalls)
        }

    // ---- helpers ----

    private fun utc(
        year: Int,
        month: Int,
        day: Int,
    ): Instant = Instant.fromEpochMilliseconds(LocalDate(year, month, day).toEpochDays() * 86_400_000L)

    private fun photo(
        id: String,
        createdAt: Instant,
    ): MediaItem = item(id, MediaType.PHOTO, createdAt)

    private fun video(
        id: String,
        createdAt: Instant,
    ): MediaItem = item(id, MediaType.VIDEO, createdAt)

    private fun item(
        id: String,
        type: MediaType,
        createdAt: Instant,
    ): MediaItem =
        MediaItem(
            id = id,
            type = type,
            createdAt = createdAt,
            durationMs = if (type == MediaType.VIDEO) 5_000L else null,
            widthPx = 120,
            heightPx = 160,
            isFavorite = false,
            isScreenshot = false,
            burstId = null,
            isCloudOnly = null,
        )
}
