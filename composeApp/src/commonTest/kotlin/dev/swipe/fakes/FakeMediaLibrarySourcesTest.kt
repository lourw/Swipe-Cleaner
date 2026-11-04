package dev.swipe.fakes

import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaType.PHOTO
import dev.swipe.domain.model.MediaType.VIDEO
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket
import dev.swipe.domain.model.SmartFilter
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * WP-5.1 source contract on [FakeMediaLibrary] (FR-5–8, §6.4): the fake must filter the
 * way PhotoKit will — type ANDed into every source, month buckets newest-first, the
 * 60 s inclusive long-video bar, burst membership, and duplicate groups without
 * singletons — because every WP-5.2 test runs against it. The grouping and bucketing
 * logic itself is the shared commonMain helpers (covered in `SourceQueriesTest` and the
 * `DuplicateGrouper` tests); this pins the fake's wiring of them.
 */
class FakeMediaLibrarySourcesTest {
    /**
     * A small library touching every source: a photo and a short video in March 2026
     * (both in album `a1`), a 90 s video in February, a screenshot, a burst photo, a
     * selfie, and a same-dimension pair 1 s apart in April — plus a lone third shot of
     * that pair 30 s later, which must stay a singleton (FR-6).
     */
    private fun library() =
        FakeMediaLibrary(
            items =
                listOf(
                    item("photo", PHOTO, utc(2026, 3, 10)),
                    item("short-video", VIDEO, utc(2026, 3, 9), durationMs = 5_000L),
                    item("long-video", VIDEO, utc(2026, 2, 20), durationMs = 90_000L),
                    item("exact-60", VIDEO, utc(2026, 2, 21), durationMs = 60_000L),
                    item("under-60", VIDEO, utc(2026, 2, 22), durationMs = 59_999L),
                    item("shot", PHOTO, utc(2026, 3, 1), isScreenshot = true),
                    item("burst-frame", PHOTO, utc(2026, 3, 2), burstId = "burst-1"),
                    item("selfie", PHOTO, utc(2026, 3, 3)),
                    item("dup-1", PHOTO, utc(2026, 4, 1, 9, 0)),
                    item("dup-2", PHOTO, utc(2026, 4, 1, 9, 0, 1)),
                    item("dup-lone", PHOTO, utc(2026, 4, 1, 9, 0, 31)),
                ),
            albums = mapOf("a1" to setOf("photo", "short-video")),
            selfieIds = setOf("selfie"),
            albumTitles = mapOf("a1" to "Road Trip"),
        )

    @Test
    fun `FR-5 type filter applies on top of every source`() =
        runTest {
            val library = library()
            val sources =
                listOf(
                    DeckSource.All,
                    DeckSource.Album("a1", "Road Trip"),
                    DeckSource.Month(2026, 3),
                    DeckSource.Smart(SmartFilter.SCREENSHOTS),
                    DeckSource.Smart(SmartFilter.LONG_VIDEOS),
                    DeckSource.Smart(SmartFilter.BURSTS),
                    DeckSource.Smart(SmartFilter.SELFIES),
                    DeckSource.Smart(SmartFilter.LIKELY_DUPLICATES),
                )
            val types = library.items.associate { it.id to it.type }
            for (source in sources) {
                for (
                (type, allowed) in
                mapOf(
                    MediaTypeFilter.PHOTOS to setOf(PHOTO),
                    MediaTypeFilter.VIDEOS to setOf(VIDEO),
                )
                ) {
                    val ids = library.assetIds(source, type)
                    assertEquals(
                        emptySet(),
                        ids.filterNot { types.getValue(it) in allowed }.toSet(),
                        "$source with $type leaked the other media type",
                    )
                }
            }
        }

    @Test
    fun `FR-6 long video deck excludes 59s and includes 60s longest first`() =
        runTest {
            val library = library()
            assertEquals(
                listOf("long-video", "exact-60"),
                library.assetIds(DeckSource.Smart(SmartFilter.LONG_VIDEOS), MediaTypeFilter.ALL),
            )
            // The type filter ANDs in: Photos never contains videos (FR-5).
            assertEquals(
                emptyList(),
                library.assetIds(DeckSource.Smart(SmartFilter.LONG_VIDEOS), MediaTypeFilter.PHOTOS),
            )
        }

    @Test
    fun `FR-6 burst source returns only assets with a burst identifier`() =
        runTest {
            val library = library()
            assertEquals(listOf("burst-frame"), library.assetIds(DeckSource.Smart(SmartFilter.BURSTS), MediaTypeFilter.ALL))
        }

    @Test
    fun `FR-6 duplicate deck keeps runs and drops singletons`() =
        runTest {
            val library = library()
            // dup-1 + dup-2 are 1 s apart at identical dimensions → a run, newest first;
            // dup-lone is 30 s after its predecessor → singleton, excluded (FR-6, §6.5).
            assertEquals(
                listOf("dup-2", "dup-1"),
                library.assetIds(DeckSource.Smart(SmartFilter.LIKELY_DUPLICATES), MediaTypeFilter.ALL),
            )
        }

    @Test
    fun `FR-7 months buckets the library newest first with type applied`() =
        runTest {
            val library = library()
            assertEquals(
                listOf(
                    MonthBucket(2026, 4, 3),
                    MonthBucket(2026, 3, 5),
                    MonthBucket(2026, 2, 3),
                ),
                library.months(MediaTypeFilter.ALL),
            )
            assertEquals(
                listOf(MonthBucket(2026, 3, 1), MonthBucket(2026, 2, 3)),
                library.months(MediaTypeFilter.VIDEOS),
            )
            // WP-5.2 asserts Home never pays for this scan at startup: every call counts.
            assertEquals(2, library.monthsCalls)
        }

    @Test
    fun `FR-7 month deck matches its bucket membership`() =
        runTest {
            val library = library()
            // The bucket says March 2026 has 5 items; the deck opened from it agrees.
            assertEquals(5, library.months(MediaTypeFilter.ALL).first { it.month == 3 }.count)
            assertEquals(5, library.assetIds(DeckSource.Month(2026, 3), MediaTypeFilter.ALL).size)
        }

    @Test
    fun `FR-8 albums carry titles counts and newest cover`() =
        runTest {
            val library = library()
            val albums = library.albums()
            assertEquals(1, albums.size)
            val album = albums.single()
            assertEquals("a1", album.id)
            assertEquals("Road Trip", album.title)
            assertEquals(2, album.count)
            // Cover is the newest member (FR-8: row thumbnail via MediaLoader).
            assertEquals("photo", album.coverAssetId)
            assertEquals(1, library.albumsCalls)
        }

    @Test
    fun `FR-6 smartCounts totals every smart filter and narrows by type`() =
        runTest {
            val library = library()
            assertEquals(
                mapOf(
                    SmartFilter.SCREENSHOTS to 1,
                    SmartFilter.LONG_VIDEOS to 2,
                    SmartFilter.BURSTS to 1,
                    SmartFilter.LIKELY_DUPLICATES to 2,
                    SmartFilter.SELFIES to 1,
                ),
                library.smartCounts(MediaTypeFilter.ALL),
            )
            assertEquals(
                mapOf(
                    SmartFilter.SCREENSHOTS to 0,
                    SmartFilter.LONG_VIDEOS to 2,
                    SmartFilter.BURSTS to 0,
                    SmartFilter.LIKELY_DUPLICATES to 0,
                    SmartFilter.SELFIES to 0,
                ),
                library.smartCounts(MediaTypeFilter.VIDEOS),
            )
            assertEquals(2, library.smartCountsCalls)
        }

    private fun item(
        id: String,
        type: MediaType,
        createdAt: Instant,
        durationMs: Long? = if (type == VIDEO) 5_000L else null,
        isScreenshot: Boolean = false,
        burstId: String? = null,
    ) = MediaItem(
        id = id,
        type = type,
        createdAt = createdAt,
        durationMs = durationMs,
        widthPx = 100,
        heightPx = 100,
        isFavorite = false,
        isScreenshot = isScreenshot,
        burstId = burstId,
        isCloudOnly = null,
    )

    private fun utc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
        second: Int = 0,
    ): Instant = LocalDateTime(year, month, day, hour, minute, second).toInstant(TimeZone.UTC)
}
