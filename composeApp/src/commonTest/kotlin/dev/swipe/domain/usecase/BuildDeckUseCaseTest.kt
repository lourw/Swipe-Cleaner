package dev.swipe.domain.usecase

import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DuplicateSetMembership
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeShuffleExclusionStore
import dev.swipe.fakes.buildDeckUseCase
import kotlinx.coroutines.flow.first
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

            val deck = buildDeckUseCase(library, store)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids

            assertEquals(setOf("b", "d"), deck.toSet())
        }

    @Test
    fun `FR-30 queued deletes stay out of the deck after a reset`() =
        runTest {
            // FR-30: Reset all decisions clears KEEP rows only, so the kept id is
            // back in the deck while the queued DELETE (FR-25) stays out of it and
            // stays on the delete queue.
            val library =
                FakeMediaLibrary(
                    listOf(photo("a", 0L), photo("b", 1L), photo("c", 2L)),
                )
            val store = FakeDecisionStore()
            store.record("a", Decision.KEEP, "old-session")
            store.record("b", Decision.DELETE, "old-session")

            store.clearKeeps()
            val deck = buildDeckUseCase(library, store)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids

            assertEquals(setOf("a", "c"), deck.toSet())
            assertEquals(listOf("b"), store.deleteQueue().first())
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

            val deck = buildDeckUseCase(library, store)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids

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

            val deck = buildDeckUseCase(library, store)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids

            assertEquals(emptyList(), deck)
        }

    @Test
    fun `6-5 shuffle is stable for the same seed`() =
        runTest {
            val library = FakeMediaLibrary((0L until 10L).map { photo("p$it", it) })
            val store = FakeDecisionStore()
            val build = buildDeckUseCase(library, store)

            val first = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L).ids
            val second = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L).ids

            assertEquals(first, second)
            assertEquals((0L until 10L).map { "p$it" }.toSet(), first.toSet())
        }

    @Test
    fun `6-5 shuffle differs for a different seed`() =
        runTest {
            val library = FakeMediaLibrary((0L until 20L).map { photo("p$it", it) })
            val store = FakeDecisionStore()
            val build = buildDeckUseCase(library, store)

            val first = build(DeckSource.All, MediaTypeFilter.ALL, seed = 1L).ids
            val second = build(DeckSource.All, MediaTypeFilter.ALL, seed = 2L).ids

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
            val build = buildDeckUseCase(library, store)

            val full = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L).ids
            val first = full[2]
            val second = full[5]
            store.record(first, Decision.KEEP, "session-1")
            store.record(second, Decision.DELETE, "session-1")

            val rebuilt = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L).ids

            assertEquals(full.filterNot { it == first || it == second }, rebuilt)
        }

    @Test
    fun `FR-20 one id added and one removed keeps the survivors' relative order`() =
        runTest {
            // The per-id key ordering (FR-20, §6.5): a library edit between
            // sessions never re-keys the survivors, so resume lands on the same
            // neighbourhood of cards. The new id may land anywhere; everything
            // else keeps its order.
            val library = FakeMediaLibrary((0L until 10L).map { photo("p$it", it) })
            val store = FakeDecisionStore()
            val build = buildDeckUseCase(library, store)

            val full = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L).ids

            library.items.removeAll { it.id == "p4" }
            library.items += photo("p10", 10L)

            val rebuilt = build(DeckSource.All, MediaTypeFilter.ALL, seed = 42L).ids

            assertEquals(full.filterNot { it == "p4" }, rebuilt.filterNot { it == "p10" })
        }

    @Test
    fun `FR-37 excluded albums keep their items out of the shuffle`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    (0L until 5L).map { photo("p$it", it) },
                    albums = mapOf("a1" to setOf("p1", "p3")),
                )
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("a1"))

            val deck = buildDeckUseCase(library, FakeDecisionStore(), exclusions)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids

            assertEquals(setOf("p0", "p2", "p4"), deck.toSet())
        }

    @Test
    fun `FR-37 turning the exclusion off puts the items back`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    (0L until 5L).map { photo("p$it", it) },
                    albums = mapOf("a1" to setOf("p1", "p3")),
                )
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("a1"))
            val build = buildDeckUseCase(library, FakeDecisionStore(), exclusions)

            val excluded = build(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids
            exclusions.setExcluded("a1", false)
            val included = build(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids

            assertEquals(setOf("p0", "p2", "p4"), excluded.toSet())
            assertEquals((0L until 5L).map { "p$it" }.toSet(), included.toSet())
        }

    @Test
    fun `FR-37 an item in two albums leaves the shuffle when either is excluded`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    (0L until 4L).map { photo("p$it", it) },
                    albums =
                        mapOf(
                            "a1" to setOf("p1"),
                            "a2" to setOf("p1", "p2"),
                        ),
                )
            // a1 stays in; p1 still leaves through its a2 membership.
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("a2"))

            val deck = buildDeckUseCase(library, FakeDecisionStore(), exclusions)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids

            assertEquals(setOf("p0", "p3"), deck.toSet())
        }

    @Test
    fun `FR-37 an exclusion for a deleted album changes nothing`() =
        runTest {
            // The store keeps ids, the library has moved on: an id with no
            // album resolves to no members, so the deck is untouched.
            val library =
                FakeMediaLibrary(
                    (0L until 4L).map { photo("p$it", it) },
                    albums = mapOf("a1" to setOf("p1")),
                )
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("gone"))

            val deck = buildDeckUseCase(library, FakeDecisionStore(), exclusions)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids

            assertEquals((0L until 4L).map { "p$it" }.toSet(), deck.toSet())
        }

    @Test
    fun `FR-37 excluded shuffle keeps the seeded relative order`() =
        runTest {
            // Subtraction commutes with the keyed ordering: survivors of the
            // exclusion sit in the same relative order as the full seeded deck,
            // so a mid-session toggle never reshuffles what resume reopens.
            val library =
                FakeMediaLibrary(
                    (0L until 10L).map { photo("p$it", it) },
                    albums = mapOf("a1" to setOf("p2", "p5", "p8")),
                )
            val store = FakeDecisionStore()
            val full = buildDeckUseCase(library, store)(DeckSource.All, MediaTypeFilter.ALL, seed = 42L).ids
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("a1"))

            val deck = buildDeckUseCase(library, store, exclusions)(DeckSource.All, MediaTypeFilter.ALL, seed = 42L).ids

            assertEquals(full.filterNot { it in setOf("p2", "p5", "p8") }, deck)
        }

    @Test
    fun `FR-37 exclusions reach only the shuffle and never an album deck`() =
        runTest {
            // Opening the excluded album itself still shows all of its items
            // (FR-37): the subtraction lives on the DeckSource.All path only.
            val library =
                FakeMediaLibrary(
                    (0L until 4L).map { photo("p$it", it) },
                    albums = mapOf("a1" to setOf("p1", "p2")),
                )
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("a1"))
            val build = buildDeckUseCase(library, FakeDecisionStore(), exclusions)

            val albumDeck = build(DeckSource.Album("a1", "Trip"), MediaTypeFilter.ALL, seed = 7L).ids

            assertEquals(setOf("p1", "p2"), albumDeck.toSet())
        }

    @Test
    fun `FR-5 the type filter and exclusions both apply to the shuffle`() =
        runTest {
            val library =
                FakeMediaLibrary(
                    listOf(photo("p1", 0L), photo("p2", 1L), video("v1", 5_000L), video("v2", 6_000L)),
                    albums = mapOf("a1" to setOf("p2", "v1")),
                )
            val exclusions = FakeShuffleExclusionStore(excluded = setOf("a1"))
            val build = buildDeckUseCase(library, FakeDecisionStore(), exclusions)

            val photos = build(DeckSource.All, MediaTypeFilter.PHOTOS, seed = 7L).ids
            val all = build(DeckSource.All, MediaTypeFilter.ALL, seed = 7L).ids

            assertEquals(setOf("p1"), photos.toSet())
            assertEquals(setOf("p1", "v2"), all.toSet())
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
                buildDeckUseCase(library, store)(
                    DeckSource.Smart(SmartFilter.LONG_VIDEOS),
                    MediaTypeFilter.ALL,
                    seed = 0L,
                ).ids

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

            val built =
                buildDeckUseCase(library, store)(
                    DeckSource.Smart(SmartFilter.LIKELY_DUPLICATES),
                    MediaTypeFilter.ALL,
                    seed = 0L,
                )

            val deck = built.ids
            assertEquals(listOf("b", "a"), deck)
            // The candidate pass runs exactly once (the FR-35 budget: the
            // memberships ride the FR-6 pass, no second scan).
            assertEquals(listOf(MediaTypeFilter.ALL), library.duplicateCandidatesCalls)
            assertEquals(emptyList(), library.assetIdsCalls)
        }

    @Test
    fun `FR-35 memberships cover the full set including decided members`() =
        runTest {
            // A 4-photo burst (all within 2 s, same dimensions); `b` is decided
            // away. The deck drops `b`, but the surviving cards still read
            // "n of 4": memberships come from the unfiltered candidates (§6.5).
            // The fake answers newest-first (§6.4), so the set order is d c b a.
            val library =
                FakeMediaLibrary(
                    listOf(
                        photo("a", 0L),
                        photo("b", 500L),
                        photo("c", 1_000L),
                        photo("d", 1_500L),
                    ),
                )
            val store = FakeDecisionStore()
            store.record("b", Decision.DELETE, "old-session")

            val built =
                buildDeckUseCase(library, store)(
                    DeckSource.Smart(SmartFilter.LIKELY_DUPLICATES),
                    MediaTypeFilter.ALL,
                    seed = 0L,
                )

            // Deck: FR-6 order minus the decided member.
            assertEquals(listOf("d", "c", "a"), built.ids)
            // Every deck id carries a membership; positions follow the set order.
            assertEquals(
                DuplicateSetMembership(memberIds = listOf("d", "c", "b", "a"), position = 0),
                built.duplicateSets["d"],
            )
            assertEquals(
                DuplicateSetMembership(memberIds = listOf("d", "c", "b", "a"), position = 1),
                built.duplicateSets["c"],
            )
            assertEquals(
                DuplicateSetMembership(memberIds = listOf("d", "c", "b", "a"), position = 3),
                built.duplicateSets["a"],
            )
            // The map describes the SETS, not the deck: the decided member has
            // a membership (position 2), but no card is ever built for it.
            assertEquals(
                DuplicateSetMembership(memberIds = listOf("d", "c", "b", "a"), position = 2),
                built.duplicateSets["b"],
            )
            // And still only the one candidate pass (§6.5), no assetIds fetch.
            assertEquals(listOf(MediaTypeFilter.ALL), library.duplicateCandidatesCalls)
            assertEquals(emptyList(), library.assetIdsCalls)
        }

    @Test
    fun `FR-35 other sources carry no memberships`() =
        runTest {
            val library = FakeMediaLibrary((0L until 5L).map { photo("p$it", it) })
            val store = FakeDecisionStore()

            val shuffle = buildDeckUseCase(library, store)(DeckSource.All, MediaTypeFilter.ALL, seed = 7L)
            val month =
                buildDeckUseCase(library, store)(
                    DeckSource.Month(2026, 1),
                    MediaTypeFilter.ALL,
                    seed = 7L,
                )

            assertEquals(emptyMap(), shuffle.duplicateSets)
            assertEquals(emptyMap(), month.duplicateSets)
            assertEquals(5, shuffle.ids.size)
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
