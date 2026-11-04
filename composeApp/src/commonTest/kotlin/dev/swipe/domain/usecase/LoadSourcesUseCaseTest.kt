package dev.swipe.domain.usecase

import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The progressive Home counts (FR-4, FR-5, FR-9, NFR-4, §6.5 *Home counts*): the
 * cheap fetches stream first, the full-library smart pass lands after them, and
 * the FR-9 refinement converges every row to the exact deck size.
 */
class LoadSourcesUseCaseTest {
    @Test
    fun `NFR-4 smart counts arrive after the cheap rows while the library pass hangs`() =
        runTest {
            // Bursts/Likely Duplicates make the real smartCounts(type) a full-library
            // pass; the fake gate stands in for that latency (WP-5.2 seam).
            val library =
                FakeMediaLibrary(
                    photoItems(3),
                    albums = mapOf("a1" to setOf("asset-1", "asset-2")),
                )
            library.gateNextSmartCounts = true
            val useCase = LoadSourcesUseCase(library, FakeDecisionStore())

            useCase(MediaTypeFilter.ALL).test {
                // Albums answer first — estimates and covers, no enumeration (NFR-4).
                val albumsFirst = awaitItem()
                assertEquals(1, albumsFirst.albums?.size)
                assertNull(albumsFirst.shuffleCount)
                assertTrue(albumsFirst.smartCounts.isEmpty())

                // Then the ids-only Shuffle count (FR-4).
                val withShuffle = awaitItem()
                assertEquals(3, withShuffle.shuffleCount)
                assertTrue(withShuffle.smartCounts.isEmpty())

                // The smart pass is still in flight: no further emission, and the
                // rows already on screen stay valid (NFR-4).
                library.releaseSmartCounts()

                val withSmart = awaitItem()
                assertEquals(3, withSmart.shuffleCount)
                assertEquals(
                    mapOf(
                        SmartFilter.SCREENSHOTS to 0,
                        SmartFilter.LONG_VIDEOS to 0,
                        SmartFilter.BURSTS to 0,
                        SmartFilter.LIKELY_DUPLICATES to 0,
                        SmartFilter.SELFIES to 0,
                    ),
                    withSmart.smartCounts,
                )
                awaitComplete() // nothing decided → the estimates are already exact
            }
        }

    @Test
    fun `FR-9 refined counts exclude decided items`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(
                        photo("p1"),
                        photo("p2"),
                        photo("p3"),
                        photo("p4"),
                        photo("ss1", screenshot = true),
                    ),
                    albums = mapOf("a1" to setOf("p1", "p2")),
                )
            val store = FakeDecisionStore()
            store.record("p1", Decision.KEEP, "s") // KEEP and pending DELETE both
            store.record("p2", Decision.DELETE, "s") // count as decided (FR-9)
            val useCase = LoadSourcesUseCase(library, store)

            useCase(MediaTypeFilter.ALL).test {
                val albums = awaitItem()
                assertEquals(2, albums.albums?.single()?.count) // estimate, pre-refinement
                val shuffle = awaitItem()
                assertEquals(5, shuffle.shuffleCount) // raw, pre-refinement
                val smart = awaitItem()
                assertEquals(1, smart.smartCounts[SmartFilter.SCREENSHOTS]) // raw, pre-refinement

                // Refinement, one emission per row whose number moves: shuffle 5 → 3
                // (p3, p4, ss1), Screenshots stays 1 (ss1 is undecided, so no
                // emission), then the album 2 → 0 (both members decided).
                val refinedShuffle = awaitItem()
                assertEquals(3, refinedShuffle.shuffleCount)

                val refinedAlbums = awaitItem()
                assertEquals(0, refinedAlbums.albums?.single()?.count)
                assertEquals(3, refinedAlbums.shuffleCount)
                assertEquals(1, refinedAlbums.smartCounts[SmartFilter.SCREENSHOTS])
                awaitComplete()
            }
            // Empty smart sources (raw 0) are already exact — no second full pass for
            // them, and the Screenshots refinement ran because its raw count was 1.
            val smartPasses = library.assetIdsCalls.map { it.first }
            assertTrue(DeckSource.Smart(SmartFilter.LONG_VIDEOS) !in smartPasses)
            assertTrue(DeckSource.Smart(SmartFilter.SCREENSHOTS) in smartPasses)
        }

    @Test
    fun `FR-5 the type filter applies to every source count`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(
                        photo("p1"),
                        photo("p2"),
                        video("v1"),
                        video("v2"),
                    ),
                    albums = mapOf("a1" to setOf("p1", "v1", "v2")),
                )
            val useCase = LoadSourcesUseCase(library, FakeDecisionStore())

            useCase(MediaTypeFilter.VIDEOS).test {
                // The album estimate covers the whole album (PhotoKit's cheap
                // estimatedAssetCount); the refinement re-fetches with the filter
                // (FR-5) and converges it to the videos-only count.
                val albums = awaitItem()
                assertEquals(3, albums.albums?.single()?.count)
                assertEquals(2, awaitItem().shuffleCount)
                awaitItem() // smart counts for VIDEOS: every source empty, so no refinement
                val refined = awaitItem()
                assertEquals(2, refined.albums?.single()?.count) // a1 without p1
                awaitComplete()
            }
            // Every read ran with the filter ANDed in (FR-5) — none leaked ALL.
            assertTrue(library.assetIdsCalls.isNotEmpty())
            assertTrue(library.assetIdsCalls.all { it.second == MediaTypeFilter.VIDEOS })
        }

    private fun photo(
        id: String,
        screenshot: Boolean = false,
    ): MediaItem =
        MediaItem(
            id = id,
            type = MediaType.PHOTO,
            createdAt = null,
            durationMs = null,
            widthPx = 120,
            heightPx = 160,
            isFavorite = false,
            isScreenshot = screenshot,
            burstId = null,
            isCloudOnly = null,
        )

    private fun video(id: String): MediaItem =
        MediaItem(
            id = id,
            type = MediaType.VIDEO,
            createdAt = null,
            durationMs = 5_000L,
            widthPx = 1920,
            heightPx = 1080,
            isFavorite = false,
            isScreenshot = false,
            burstId = null,
            isCloudOnly = null,
        )

    private fun photoItems(count: Int): List<MediaItem> = (1..count).map { photo("asset-$it") }
}
