package dev.swipe.platform

import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Shared contract for [MediaLibrary] implementations (WP-7.7, FR-7, §6.5).
 *
 * The same assertions run against the fake (the behavioral baseline the app
 * is developed against) and, when the host permits seeding a PhotoKit
 * library, against the real `PhotoKitMediaLibrary`. The suite pins the deck
 * promises FR-7 rests on:
 *
 *  - `DeckSource.Month` decks come back creation-date descending — never in
 *    PhotoKit's natural oldest-first order (the WP-7.7 bug),
 *  - a month deck holds exactly that month's assets,
 *  - `DeckSource.All` is creation-date descending too (§6.5, the
 *    [MediaLibrary.assetIds] KDoc), so months appear newest-first,
 *  - [MediaTypeFilter] filters every deck (FR-5),
 *  - [MediaLibrary.items] resolves ids in the order requested and drops
 *    unknown ones (§6.5 step 2),
 *  - the Bursts deck enumerates **every frame** of every burst and every one
 *    of those ids resolves back through [MediaLibrary.items] (FR-6, WP-8.3),
 *  - the Bursts count and the Bursts deck agree (FR-9).
 *
 * Assertions compare *relative* order and membership against the shared
 * [fixtureAssets], never absolute id lists, so both runners can agree.
 * Tests that need the fixture in the library call [assumeSeeded]; outside a
 * seeded run they skip with an explicit assumption message rather than fail.
 */
abstract class MediaLibraryContract {
    /**
     * A deterministic fixture the seeded runs place into the library: two
     * calendar months, photos and videos, with a known creation order.
     *
     * Assets sit at mid-month 12:00 UTC so no device timezone (±14 h) can
     * shift one into a neighbouring month, and days strictly increase with
     * [order] within a month so membership and order are independent facts.
     */
    protected data class FixtureAsset(
        val name: String,
        val type: MediaType,
        /** Index into [fixtureMonths], 0 = older month. */
        val month: Int,
        /** Position within the month, ascending creation date. */
        val order: Int,
    )

    protected val fixtureAssets: List<FixtureAsset> =
        listOf(
            FixtureAsset("wp77-mar-a", MediaType.PHOTO, month = 0, order = 0),
            FixtureAsset("wp77-mar-b", MediaType.VIDEO, month = 0, order = 1),
            FixtureAsset("wp77-mar-c", MediaType.PHOTO, month = 0, order = 2),
            FixtureAsset("wp77-apr-a", MediaType.PHOTO, month = 1, order = 0),
            FixtureAsset("wp77-apr-b", MediaType.VIDEO, month = 1, order = 1),
            FixtureAsset("wp77-apr-c", MediaType.PHOTO, month = 1, order = 2),
        )

    /**
     * Result of seeding the fixture: the library's asset ids in fixture
     * creation order (oldest first), or `null` when the run could not seed.
     */
    protected class Seeded(
        val ids: List<String>?,
    ) {
        val isSeeded: Boolean get() = ids != null
    }

    /** The implementation under test. */
    protected abstract val library: MediaLibrary

    /**
     * Place [fixtureAssets] into the implementation's backing store and
     * return their ids in fixture order (oldest first). Implementations that
     * cannot seed (authorization or write failures on a shared library)
     * return [Seeded] with `null`; the asset-dependent tests then skip with
     * an assumption instead of failing.
     */
    protected abstract suspend fun seed(): Seeded

    /** Remove everything [seed] placed. Must not throw. */
    protected abstract suspend fun cleanUp(seeded: Seeded)

    protected val seeded: Seeded by lazy { runBlocking { seed() } }

    /** True when the fixture is in the library; prints an explicit skip otherwise. */
    protected fun assumeSeeded(): Boolean {
        if (!seeded.isSeeded) {
            println("SKIPPED (WP-7.7): fixture could not be seeded into this host's media library")
        }
        return seeded.isSeeded
    }

    // ---- Burst fixture (FR-6, WP-8.3) ----

    /**
     * One photo frame of a seeded burst: [id], the [burstId] shared by every
     * frame of the same burst, and a [day] of the newer fixture month (5…9,
     * clear of the base fixture's days so every creation date is distinct).
     */
    protected data class BurstFrame(
        val id: String,
        val burstId: String,
        val day: Int,
    )

    /**
     * The burst fixture: two bursts, three frames and two. PhotoKit has no API
     * to create burst assets, so only runners whose store can model bursts
     * seed them (the fake, via [dev.swipe.domain.model.MediaItem.burstId]);
     * the real runner leaves [burstFrameIds] empty and the burst cases skip
     * with a printed reason.
     */
    protected val burstFixture: List<BurstFrame> =
        listOf(
            BurstFrame("wp83-burst-a1", "wp83-burst-a", day = 5),
            BurstFrame("wp83-burst-a2", "wp83-burst-a", day = 6),
            BurstFrame("wp83-burst-a3", "wp83-burst-a", day = 7),
            BurstFrame("wp83-burst-b1", "wp83-burst-b", day = 8),
            BurstFrame("wp83-burst-b2", "wp83-burst-b", day = 9),
        )

    /** The burst-fixture ids this runner seeded; empty when bursts cannot be modeled. */
    protected open val burstFrameIds: List<String> get() = emptyList()

    /** True when the burst fixture is in the library; prints an explicit skip otherwise. */
    protected fun assumeBurstsSeeded(): Boolean {
        val ready = assumeSeeded() && burstFrameIds.isNotEmpty()
        if (!ready && seeded.isSeeded) {
            println("SKIPPED (WP-8.3): this runner cannot seed burst assets (PhotoKit has no burst write API)")
        }
        return ready
    }

    protected suspend fun idsFor(
        source: DeckSource,
        type: MediaTypeFilter,
    ): List<String> = library.assetIds(source, type)

    /** Fixture descriptions in deck order, ignoring anything not in the fixture. */
    protected fun seededFixtureOrder(ids: List<String>): List<FixtureAsset> {
        val seededIds = seeded.ids ?: return emptyList()
        val byId = fixtureAssets.indices.associateBy({ seededIds[it] }, { fixtureAssets[it] })
        return ids.mapNotNull { byId[it] }
    }

    protected fun fixtureIds(monthIndex: Int? = null): List<String> {
        val seededIds = seeded.ids ?: emptyList()
        return fixtureAssets.indices
            .filter { monthIndex == null || fixtureAssets[it].month == monthIndex }
            .map { seededIds[it] }
    }

    protected val newerMonth: MonthRef get() = fixtureMonths[1]

    protected val olderMonth: MonthRef get() = fixtureMonths[0]

    @Test
    fun `FR-7 month deck comes back creation-date descending`() {
        if (!assumeSeeded()) return
        runBlocking {
            val deck = idsFor(DeckSource.Month(newerMonth.year, newerMonth.month), MediaTypeFilter.ALL)
            val fixture = seededFixtureOrder(deck)
            // Regression (WP-7.7): the fetch dropped its sort descriptor and
            // PhotoKit answered in natural order — oldest first.
            assertEquals(3, fixture.size, "the month deck must hold the month's three fixtures, got $deck")
            assertEquals(
                listOf(2, 1, 0),
                fixture.map { it.order },
                "month deck must be newest first (§6.5), never oldest first",
            )
            // The same fact straight off the metadata: dates strictly
            // descending. Filtered to the fixture ids: a real granted library
            // may hold user assets in the fixture month, and the contract
            // compares only against its own fixtures (class KDoc).
            val seeded = fixtureIds().toSet()
            val dates = library.items(deck.filter { it in seeded }).mapNotNull { it.createdAt }
            assertEquals(3, dates.size)
            assertTrue(
                dates.zipWithNext().all { (a, b) -> a > b },
                "month deck dates must be strictly descending: $dates",
            )
        }
    }

    @Test
    fun `FR-2 assetCount equals the Shuffle id listing`() {
        if (!assumeSeeded()) return
        runBlocking {
            // The FR-2 banner's N must be exactly the size of the library-wide id
            // listing — the fetch count and the enumeration may never drift, in the
            // fake or in PhotoKit (NFR-4).
            assertEquals(idsFor(DeckSource.All, MediaTypeFilter.ALL).size, library.assetCount())
        }
    }

    @Test
    fun `FR-7 month deck holds exactly that month`() {
        if (!assumeSeeded()) return
        runBlocking {
            for (index in fixtureMonths.indices) {
                val ref = fixtureMonths[index]
                val deck = idsFor(DeckSource.Month(ref.year, ref.month), MediaTypeFilter.ALL)
                val own = fixtureIds(index).toSet()
                val other = fixtureIds(1 - index).toSet()
                assertTrue(own.all { it in deck }, "month ${ref.year}-${ref.month} deck must hold its fixtures: $deck")
                assertTrue(other.none { it in deck }, "month ${ref.year}-${ref.month} deck must exclude the other month: $deck")
            }
        }
    }

    @Test
    fun `FR-5 month deck applies the type filter`() {
        if (!assumeSeeded()) return
        runBlocking {
            for (index in fixtureMonths.indices) {
                val ref = fixtureMonths[index]
                val source = DeckSource.Month(ref.year, ref.month)
                val monthsPhotos =
                    fixtureAssets
                        .filter { it.month == index && it.type != MediaType.VIDEO }
                        .map { seeded.ids!![fixtureAssets.indexOf(it)] }
                        .toSet()
                val monthsVideos =
                    fixtureAssets
                        .filter { it.month == index && it.type == MediaType.VIDEO }
                        .map { seeded.ids!![fixtureAssets.indexOf(it)] }
                        .toSet()
                val photos = idsFor(source, MediaTypeFilter.PHOTOS).toSet()
                val videos = idsFor(source, MediaTypeFilter.VIDEOS).toSet()
                assertEquals(monthsPhotos, photos.intersect(monthsPhotos + monthsVideos), "PHOTOS month deck")
                assertEquals(monthsVideos, videos.intersect(monthsPhotos + monthsVideos), "VIDEOS month deck")
            }
        }
    }

    @Test
    fun `FR-5 All deck applies the type filter`() {
        if (!assumeSeeded()) return
        runBlocking {
            val allPhotos = seededFixtureOrder(idsFor(DeckSource.All, MediaTypeFilter.PHOTOS))
            val allVideos = seededFixtureOrder(idsFor(DeckSource.All, MediaTypeFilter.VIDEOS))
            val all = seededFixtureOrder(idsFor(DeckSource.All, MediaTypeFilter.ALL))
            assertEquals(fixtureAssets.count { it.type != MediaType.VIDEO }, allPhotos.size, "PHOTOS deck holds exactly the photos")
            assertEquals(fixtureAssets.count { it.type == MediaType.VIDEO }, allVideos.size, "VIDEOS deck holds exactly the videos")
            assertEquals(fixtureAssets.size, all.size, "ALL deck holds the whole fixture")
        }
    }

    @Test
    fun `FR-65 All deck is creation-date descending so months come newest first`() {
        if (!assumeSeeded()) return
        runBlocking {
            val deck = idsFor(DeckSource.All, MediaTypeFilter.ALL)
            val fixture = seededFixtureOrder(deck)
            val expected =
                fixtureAssets
                    .sortedWith(compareByDescending<FixtureAsset> { it.month }.thenByDescending { it.order })
            assertEquals(expected, fixture, "All deck must be creation-date descending across months (§6.5)")
            // And explicitly not the natural ascending order the bug produced.
            assertTrue(
                fixture != fixtureAssets.sortedWith(compareBy({ it.month }, { it.order })),
                "All deck must not be oldest first",
            )
        }
    }

    @Test
    fun `items resolves fixture ids in the order requested`() {
        if (!assumeSeeded()) return
        runBlocking {
            val requested = seeded.ids!!.reversed()
            val resolved = library.items(requested)
            assertEquals(requested, resolved.map { it.id }, "items must keep the input order (§6.5 step 2)")
            val expectedTypes = requested.map { fixtureAssets[seeded.ids!!.indexOf(it)].type }
            assertEquals(expectedTypes, resolved.map { it.type }, "resolved metadata must match the fixture")
        }
    }

    @Test
    fun `items drops ids the library does not have`() {
        if (!assumeSeeded()) return
        runBlocking {
            val requested = listOf("wp77-no-such-asset") + seeded.ids!!
            val resolved = library.items(requested)
            assertEquals(seeded.ids, resolved.map { it.id }, "unknown ids drop out, the rest resolve in order")
        }
    }

    // ---- Bursts (FR-6, WP-8.3) ----

    @Test
    fun `FR-6 the burst deck enumerates every frame of every burst`() {
        if (!assumeBurstsSeeded()) return
        runBlocking {
            val deck = idsFor(DeckSource.Smart(SmartFilter.BURSTS), MediaTypeFilter.ALL)
            // The empty-Bursts-deck bug started here: a deck source that answers
            // without the frames the count promised.
            assertEquals(
                burstFrameIds.toSet(),
                deck.toSet(),
                "the Bursts deck must hold every frame of every seeded burst",
            )
            // And in deck order (§6.5): creation date descending.
            val days = deck.map { id -> burstFixture.first { it.id == id }.day }
            assertEquals(days.sortedDescending(), days, "burst deck must be newest first (§6.5)")
        }
    }

    @Test
    fun `FR-6 the id lookup resolves every burst frame the deck lists`() {
        if (!assumeBurstsSeeded()) return
        runBlocking {
            // WP-8.3 regression, at the contract level: an id a source fetch
            // enumerated must resolve back through items(). The shipped bug
            // enumerated burst frames with includeAllBurstAssets but looked them
            // up with nil options — the default view carries one representative
            // per burst, so the deck's burst ids resolved to nothing and every
            // burst deck opened empty.
            val resolved = library.items(burstFrameIds)
            assertEquals(burstFrameIds, resolved.map { it.id }, "every burst frame must resolve, in request order")
            val byBurst = burstFixture.associate { it.id to it.burstId }
            assertEquals(
                burstFrameIds.map { byBurst.getValue(it) },
                resolved.map { it.burstId },
                "resolved frames must carry their burst identity (FR-6)",
            )
        }
    }

    /** The fixture calendar months, older first. */
    protected data class MonthRef(
        val year: Int,
        val month: Int,
    )

    protected companion object {
        /**
         * Fixed calendar months the fixture dates into (deliberately in the
         * past so they never collide with freshly captured device media).
         * Index 0 is the older month.
         */
        val fixtureMonths: List<MonthRef> =
            listOf(
                MonthRef(2025, 3),
                MonthRef(2025, 4),
            )

        /** Unix epoch seconds for 12:00 UTC on [day] of [ref]. */
        fun epochSeconds(
            ref: MonthRef,
            day: Int,
        ): Long {
            var days = 0L
            var y = 1970
            while (y < ref.year) {
                days += if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 366 else 365
                y++
            }
            val monthLengths = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            val leap = ref.year % 4 == 0 && (ref.year % 100 != 0 || ref.year % 400 == 0)
            for (m in 1 until ref.month) {
                days += if (m == 2 && leap) 29 else monthLengths[m - 1]
            }
            days += day - 1
            return days * 86_400L + 12L * 3_600L
        }

        /**
         * Fixture day of the month: photos on 3/17/24, videos on 10/20, so
         * days strictly increase with [FixtureAsset.order] within a month
         * (3 < 20 < 24) and never tie across types.
         */
        fun dayOf(asset: FixtureAsset): Int {
            val days =
                when (asset.type) {
                    MediaType.PHOTO, MediaType.LIVE_PHOTO -> intArrayOf(3, 17, 24)
                    MediaType.VIDEO -> intArrayOf(10, 20)
                }
            require(asset.order in days.indices) {
                "FixtureAsset(${asset.type}, order=${asset.order}) has no day in $days; " +
                    "extend dayOf and the fixture dates together"
            }
            return days[asset.order]
        }

        /** [Instant] for fixture asset [asset]'s creation date (12:00 UTC). */
        fun createdAt(asset: FixtureAsset): Instant = Instant.fromEpochSeconds(epochSeconds(fixtureMonths[asset.month], dayOf(asset)))
    }
}
