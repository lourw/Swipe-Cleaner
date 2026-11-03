package dev.swipe.domain.usecase

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
import kotlin.test.assertNotEquals
import kotlin.time.Instant

/**
 * §6.5 deck building on the fakes: FR-9 subtraction, the seeded shuffle, the
 * pre-sorted long-video source, and the Likely Duplicates wiring through
 * [MediaLibrary.duplicateCandidates][dev.swipe.data.MediaLibrary.duplicateCandidates].
 * Fixed seeds throughout, no real randomness (§8.2).
 */
class BuildDeckUseCaseTest {
    @Test
    fun `FR-9 deck excludes kept and queued items`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(photo("a", 0L), photo("b", 1L), photo("c", 2L), photo("d", 3L)),
                )
            val store = FakeDecisionStore()
            store.record("a", Decision.KEEP, "old-session")
            store.record("c", Decision.DELETE, "old-session")

            val deck = BuildDeckUseCase(library, store)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L)

            assertEquals(setOf("b", "d"), deck.toSet())
        }

    @Test
    fun `FR-9 deck includes previously skipped items`() =
        runTest {
            // A skip lives in session_skip only (FR-14), so decidedIds() never holds it
            // and the next session's deck contains the item again.
            val library =
                FakeMediaLibrary(
                    listOf(photo("a", 0L), photo("b", 1L), photo("c", 2L)),
                )
            val store = FakeDecisionStore()
            store.record("b", Decision.SKIP, "old-session")

            val deck = BuildDeckUseCase(library, store)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L)

            assertEquals(setOf("a", "b", "c"), deck.toSet())
        }

    @Test
    fun `FR-9 deck is empty when every item is decided`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(photo("a", 0L), photo("b", 1L)),
                )
            val store = FakeDecisionStore()
            store.record("a", Decision.KEEP, "old-session")
            store.record("b", Decision.DELETE, "old-session")

            val deck = BuildDeckUseCase(library, store)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L)

            assertEquals(emptyList(), deck)
        }

    @Test
    fun `6-5 shuffle is stable for the same seed`() =
        runTest {
            val library = FakeMediaLibrary((0L until 10L).map { photo("p$it", it) })
            val store = FakeDecisionStore()
            val build = BuildDeckUseCase(library, store)

            val first = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L)
            val second = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L)

            assertEquals(first, second)
            assertEquals((0L until 10L).map { "p$it" }.toSet(), first.toSet())
        }

    @Test
    fun `6-5 shuffle differs for a different seed`() =
        runTest {
            val library = FakeMediaLibrary((0L until 20L).map { photo("p$it", it) })
            val store = FakeDecisionStore()
            val build = BuildDeckUseCase(library, store)

            val first = build(DeckSource.All, MediaTypeFilter.ALL, seed = 1L)
            val second = build(DeckSource.All, MediaTypeFilter.ALL, seed = 2L)

            assertNotEquals(first, second)
        }

    @Test
    fun `6-5 seeded order survives decided subtraction`() =
        runTest {
            // The shuffle runs on the full id list before decided items are subtracted,
            // so a rebuild mid-session keeps the same relative order — what resume
            // needs to reopen a session on the same card (FR-20).
            val library = FakeMediaLibrary((0L until 10L).map { photo("p$it", it) })
            val store = FakeDecisionStore()
            val build = BuildDeckUseCase(library, store)

            val full = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L)
            val first = full[2]
            val second = full[5]
            store.record(first, Decision.KEEP, "session-1")
            store.record(second, Decision.DELETE, "session-1")

            val rebuilt = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L)

            assertEquals(full.filterNot { it == first || it == second }, rebuilt)
        }

    @Test
    fun `FR-6 long videos are longest first`() =
        runTest {
            // The library returns this source pre-sorted duration-descending (§6.5);
            // 45 s is under the 60 s bar and a photo never qualifies.
            val library =
                FakeMediaLibrary(
                    listOf(
                        video("v-90", durationMs = 90_000L),
                        video("v-300", durationMs = 300_000L),
                        video("v-45", durationMs = 45_000L),
                        video("v-120", durationMs = 120_000L),
                        photo("p-1", 0L),
                    ),
                )
            val store = FakeDecisionStore()

            val deck =
                BuildDeckUseCase(library, store)(
                    DeckSource.Smart(SmartFilter.LONG_VIDEOS),
                    MediaTypeFilter.ALL,
                    seed = 0L,
                )

            assertEquals(listOf("v-300", "v-120", "v-90"), deck)
        }

    @Test
    fun `FR-6 likely duplicates deck groups the library candidates`() =
        runTest {
            // a+b are 1 s apart with equal dimensions; c matches neither; d is decided
            // away before grouping (FR-9). The fake hands candidates back newest-first
            // (§6.4), so the run comes out b then a — adjacent is what FR-6 requires.
            val library =
                FakeMediaLibrary(
                    listOf(
                        video("d", durationMs = 5_000L),
                        photo("a", 0L),
                        photo("b", 1_000L),
                        photo("c", 60_000L, widthPx = 2016, heightPx = 1512),
                    ),
                )
            val store = FakeDecisionStore()
            store.record("d", Decision.KEEP, "old-session")

            val deck =
                BuildDeckUseCase(library, store)(
                    DeckSource.Smart(SmartFilter.LIKELY_DUPLICATES),
                    MediaTypeFilter.ALL,
                    seed = 0L,
                )

            assertEquals(listOf("b", "a"), deck)
            assertEquals(listOf(MediaTypeFilter.ALL), library.duplicateCandidatesCalls)
            assertEquals(emptyList(), library.assetIdsCalls)
        }

    private fun photo(
        id: String,
        createdAtMs: Long,
        widthPx: Int = 100,
        heightPx: Int = 100,
    ) = MediaItem(
        id = id,
        type = MediaType.PHOTO,
        createdAt = Instant.fromEpochMilliseconds(createdAtMs),
        durationMs = null,
        widthPx = widthPx,
        heightPx = heightPx,
        isFavorite = false,
        isScreenshot = false,
        burstId = null,
        isCloudOnly = null,
    )

    private fun video(
        id: String,
        durationMs: Long,
        createdAtMs: Long = 0L,
    ) = MediaItem(
        id = id,
        type = MediaType.VIDEO,
        createdAt = Instant.fromEpochMilliseconds(createdAtMs),
        durationMs = durationMs,
        widthPx = 100,
        heightPx = 100,
        isFavorite = false,
        isScreenshot = false,
        burstId = null,
        isCloudOnly = null,
    )
}
