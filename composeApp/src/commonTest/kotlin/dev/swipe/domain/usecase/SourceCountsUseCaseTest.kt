package dev.swipe.domain.usecase

import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket
import dev.swipe.domain.model.SmartFilter
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeShuffleExclusionStore
import dev.swipe.fakes.buildDeckUseCase
import dev.swipe.fakes.sourceCountsUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The one count path (FR-4, FR-5, FR-7, FR-8, FR-9, WP-8.12, §6.5 *Home
 * counts*): every count is final-or-placeholder — PhotoKit's raw fetch size
 * and `estimatedAssetCount` are never emitted — every count is the size of the
 * deck its row opens, and a row whose fetch fails keeps its placeholder for
 * the next refresh (§6.8).
 */
class SourceCountsUseCaseTest {
    @Test
    fun `FR-5 counts are final-or-placeholder never an estimate`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(photo("p1"), photo("p2"), photo("p3"), photo("p4")),
                    albums = mapOf("a1" to setOf("p1", "p2", "p3")),
                )
            library.nextAlbumsEstimate = 99 // PhotoKit's lagging estimate — must never surface
            val store = FakeDecisionStore()
            store.record("p1", Decision.KEEP, "s")
            val useCase = sourceCountsUseCase(library, store)

            useCase(MediaTypeFilter.ALL).test {
                // 1. Row identity lands first, all counts still placeholders (NFR-4).
                val identity = awaitItem()
                assertEquals(1, identity.albums?.size)
                assertNull(identity.shuffleCount)
                assertTrue(identity.smartCounts.isEmpty())
                assertTrue(identity.albumCounts.isEmpty())

                // 2.–6. One emission per row, each carrying its FR-9-exact number:
                // shuffle 4 − 1 decided, five empty smart sources, album 3 − 1.
                assertEquals(3, awaitItem().shuffleCount)
                for (kind in SmartFilter.entries) {
                    assertEquals(0, awaitItem().smartCounts.getValue(kind))
                }
                val last = awaitItem()
                assertEquals(mapOf("a1" to 2), last.albumCounts)
                awaitComplete()
            }
            // The estimate never reached an emission, and the raw smart-count pass
            // is gone entirely — every number came off the deck path.
            assertEquals(0, library.smartCountsCalls)
        }

    @Test
    fun `FR-9 every count excludes decided items`() =
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
            val useCase = sourceCountsUseCase(library, store)

            useCase(MediaTypeFilter.ALL).test {
                awaitItem() // identity
                assertEquals(3, awaitItem().shuffleCount) // p3, p4, ss1
                val seen = mutableMapOf<SmartFilter, Int>()
                repeat(SmartFilter.entries.size) { seen += awaitItem().smartCounts }
                assertEquals(1, seen.getValue(SmartFilter.SCREENSHOTS)) // ss1 undecided
                assertEquals(mapOf("a1" to 0), awaitItem().albumCounts) // both members decided
                awaitComplete()
            }
        }

    @Test
    fun `FR-5 the type filter applies to every count`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(photo("p1"), photo("p2"), video("v1"), video("v2")),
                    albums = mapOf("a1" to setOf("p1", "v1", "v2")),
                )
            val useCase = sourceCountsUseCase(library, FakeDecisionStore())

            useCase(MediaTypeFilter.VIDEOS).test {
                awaitItem() // identity
                assertEquals(2, awaitItem().shuffleCount)
                repeat(SmartFilter.entries.size) { awaitItem() }
                assertEquals(mapOf("a1" to 2), awaitItem().albumCounts) // a1 without p1
                awaitComplete()
            }
            // Every read ran with the filter ANDed in (FR-5) — none leaked ALL.
            assertTrue(library.assetIdsCalls.isNotEmpty())
            assertTrue(library.assetIdsCalls.all { it.second == MediaTypeFilter.VIDEOS })
        }

    @Test
    fun `FR-9 the duplicates count equals the deck the row opens`() =
        runTest {
            // d1/d2 are twins (same dimensions, 1 s apart). Deciding d1 must take d2
            // out of the row too — the deck subtracts decided BEFORE grouping, and
            // plain membership-minus-decided would over-report 1 for an empty deck.
            val library =
                FakeMediaLibrary(
                    listOf(
                        twin("d1", "2026-01-01T10:00:00Z"),
                        twin("d2", "2026-01-01T10:00:01Z"),
                        photo("p3"),
                    ),
                )
            val store = FakeDecisionStore()
            store.record("d1", Decision.KEEP, "s")
            val useCase = sourceCountsUseCase(library, store)

            useCase(MediaTypeFilter.ALL).test {
                awaitItem() // identity
                assertEquals(2, awaitItem().shuffleCount) // d2, p3
                val seen = mutableMapOf<SmartFilter, Int>()
                repeat(SmartFilter.entries.size) { seen += awaitItem().smartCounts }
                // The deck's own grouping path: the lone twin d2 is no group.
                assertEquals(0, seen.getValue(SmartFilter.LIKELY_DUPLICATES))
                awaitComplete()
            }
            val deck =
                buildDeckUseCase(library, store)(
                    DeckSource.Smart(SmartFilter.LIKELY_DUPLICATES),
                    MediaTypeFilter.ALL,
                    0L,
                )
            assertEquals(0, deck.size) // count == deck size
        }

    @Test
    fun `FR-9 the bursts count equals the deck the row opens`() =
        runTest {
            // WP-8.3: the Home Bursts row opens a deck of burst frames, so the
            // row's number is the frame count — every frame of both bursts,
            // not the two bursts and not the Photos-app grouping.
            val library =
                FakeMediaLibrary(
                    listOf(
                        burstFrame("b1a", "burst-1", "2026-01-01T10:00:00Z"),
                        burstFrame("b1b", "burst-1", "2026-01-01T10:00:01Z"),
                        burstFrame("b1c", "burst-1", "2026-01-01T10:00:02Z"),
                        burstFrame("b2a", "burst-2", "2026-01-02T10:00:00Z"),
                        burstFrame("b2b", "burst-2", "2026-01-02T10:00:01Z"),
                        photo("p3"),
                    ),
                )
            val store = FakeDecisionStore()
            val useCase = sourceCountsUseCase(library, store)

            useCase(MediaTypeFilter.ALL).test {
                awaitItem() // identity
                assertEquals(6, awaitItem().shuffleCount)
                val seen = mutableMapOf<SmartFilter, Int>()
                repeat(SmartFilter.entries.size) { seen += awaitItem().smartCounts }
                assertEquals(5, seen.getValue(SmartFilter.BURSTS)) // frames, not bursts
                awaitComplete()
            }
            val deck =
                buildDeckUseCase(library, store)(
                    DeckSource.Smart(SmartFilter.BURSTS),
                    MediaTypeFilter.ALL,
                    0L,
                )
            assertEquals(setOf("b1a", "b1b", "b1c", "b2a", "b2b"), deck.toSet())
            assertEquals(5, deck.size) // count == deck size
        }

    @Test
    fun `FR-37 the shuffle count drops excluded albums`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(photo("p1"), photo("p2"), photo("p3"), photo("p4"), photo("p5")),
                    albums = mapOf("a1" to setOf("p2", "p4")),
                )
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("a1"))
            val useCase = sourceCountsUseCase(library, FakeDecisionStore(), exclusions)

            useCase(MediaTypeFilter.ALL).test {
                // The excluded album still lists, and its row keeps its full count:
                // exclusions reach only the Shuffle source (FR-37).
                awaitItem() // identity
                assertEquals(3, awaitItem().shuffleCount) // p2 and p4 left with a1
                repeat(SmartFilter.entries.size) { awaitItem() }
                assertEquals(mapOf("a1" to 2), awaitItem().albumCounts)
                awaitComplete()
            }
        }

    @Test
    fun `FR-8 the albums flow shows identity first then exact counts per row`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(photo("p1"), photo("p2"), video("v1")),
                    albums = mapOf("a1" to setOf("p1", "p2"), "a2" to setOf("v1")),
                )
            library.nextAlbumsEstimate = 0 // a wrong estimate must never surface
            val useCase = sourceCountsUseCase(library, FakeDecisionStore())

            useCase.albums(MediaTypeFilter.ALL).test {
                val identity = awaitItem()
                assertEquals(listOf("a1", "a2"), identity.rows.map { it.id })
                assertTrue(identity.counts.isEmpty()) // placeholders, not the estimate

                val withA1 = awaitItem()
                assertEquals(mapOf("a1" to 2), withA1.counts)
                val withA2 = awaitItem()
                assertEquals(mapOf("a1" to 2, "a2" to 1), withA2.counts)
                awaitComplete()
            }
        }

    @Test
    fun `FR-7 the months flow counts the month decks with decided subtracted`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(
                        photo("p1", createdAt = "2026-03-15T00:00:00Z"),
                        photo("p2", createdAt = "2026-03-02T00:00:00Z"),
                        photo("p3", createdAt = "2025-12-20T00:00:00Z"),
                    ),
                )
            val store = FakeDecisionStore()
            store.record("p1", Decision.KEEP, "s")
            val useCase = sourceCountsUseCase(library, store)

            useCase.months(MediaTypeFilter.ALL).test {
                val buckets = awaitItem()
                // The scan list first (identity), counts all placeholders.
                assertEquals(
                    listOf(MonthBucket(2026, 3, 2), MonthBucket(2025, 12, 1)),
                    buckets.rows,
                )
                assertTrue(buckets.counts.isEmpty())

                // Each bucket's exact count: March 2026 − p1 decided (FR-9).
                assertEquals(
                    mapOf("2026-3" to 1),
                    awaitItem().counts,
                )
                assertEquals(
                    mapOf("2026-3" to 1, "2025-12" to 1),
                    awaitItem().counts,
                )
                awaitComplete()
            }
            // The count and the deck the row opens are the same query.
            val deck = buildDeckUseCase(library, store)(DeckSource.Month(2026, 3), MediaTypeFilter.ALL, 0L)
            assertEquals(1, deck.size)
        }

    @Test
    fun `FR-6 a failed row fetch keeps that row's placeholder and the run continues`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(photo("p1"), photo("p2")),
                    albums = mapOf("a1" to setOf("p1")),
                )
            // The Shuffle read (the first assetIds call) fails; the run must not die.
            library.failNextAssetIds = IllegalStateException("PhotoKit said no")
            val useCase = sourceCountsUseCase(library, FakeDecisionStore())

            useCase(MediaTypeFilter.ALL).test {
                awaitItem() // identity
                val smart = awaitItem() // Screenshots answered despite the Shuffle failure
                assertEquals(0, smart.smartCounts.getValue(SmartFilter.SCREENSHOTS))
                assertNull(smart.shuffleCount) // placeholder kept (§6.8), next refresh retries
                repeat(SmartFilter.entries.size - 1) { awaitItem() }
                assertEquals(mapOf("a1" to 1), awaitItem().albumCounts)
                awaitComplete()
            }
        }

    // ---- helpers ----

    private fun photo(
        id: String,
        screenshot: Boolean = false,
        createdAt: String? = null,
    ): MediaItem =
        MediaItem(
            id = id,
            type = MediaType.PHOTO,
            createdAt = createdAt?.let(Instant::parse),
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

    /** A dated photo (same 120×160 dimensions as [photo]) so twins group (FR-6). */
    private fun twin(
        id: String,
        createdAt: String,
    ): MediaItem = photo(id, createdAt = createdAt)

    /** A burst frame (FR-6): a dated [photo] carrying a [burstId]. */
    private fun burstFrame(
        id: String,
        burstId: String,
        createdAt: String,
    ): MediaItem = photo(id, createdAt = createdAt).copy(burstId = burstId)
}
