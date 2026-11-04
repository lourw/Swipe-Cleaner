package dev.swipe.data

import dev.swipe.domain.model.MonthBucket
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * WP-5.1 pure helpers behind the source queries (FR-6, FR-7), covered without PhotoKit (§8.2).
 * All calendar checks pin [TimeZone.UTC] for determinism; the real library passes the device
 * zone, and the shared logic is what these tests protect.
 */
class SourceQueriesTest {
    @Test
    fun `FR-7 month buckets are newest first with counts`() {
        // Two March 2026, one January 2026, three December 2025, plus an undated item that
        // belongs to no bucket (FR-7).
        val dates =
            listOf(
                utc(2026, 3, 4),
                utc(2025, 12, 1),
                utc(2026, 1, 15),
                null,
                utc(2026, 3, 31),
                utc(2025, 12, 25),
                utc(2025, 12, 31),
            )

        val buckets = monthBuckets(dates, TimeZone.UTC)

        assertEquals(
            listOf(
                MonthBucket(2026, 3, 2),
                MonthBucket(2026, 1, 1),
                MonthBucket(2025, 12, 3),
            ),
            buckets,
        )
    }

    @Test
    fun `FR-7 empty library has no month buckets`() {
        assertEquals(emptyList(), monthBuckets(listOf(null, null), TimeZone.UTC))
    }

    @Test
    fun `FR-7 month membership matches the month range half-open`() {
        val (start, end) = monthRange(2026, 3, TimeZone.UTC)

        // The range is half-open: the first instant of the month is in, and the first instant of
        // the next month belongs to the NEXT bucket, so an item on the boundary lands in exactly
        // one month (FR-7).
        assertTrue(instantInMonth(start, 2026, 3, TimeZone.UTC))
        assertFalse(instantInMonth(end, 2026, 3, TimeZone.UTC))
        assertTrue(instantInMonth(end, 2026, 4, TimeZone.UTC))
        // A mid-month instant sits inside the half-open range [start, end).
        val mid = utc(2026, 3, 14)
        assertTrue(instantInMonth(mid, 2026, 3, TimeZone.UTC))
        assertTrue(mid >= start && mid < end)
        assertFalse(utc(2026, 2, 28) >= start && utc(2026, 2, 28) < end)
    }

    @Test
    fun `FR-7 December range rolls over to January of the next year`() {
        val (start, end) = monthRange(2026, 12, TimeZone.UTC)
        assertEquals(LocalDateTime(2026, 12, 1, 0, 0).toInstant(TimeZone.UTC), start)
        assertEquals(LocalDateTime(2027, 1, 1, 0, 0).toInstant(TimeZone.UTC), end)
    }

    @Test
    fun `FR-6 long video threshold is 60 seconds inclusive`() {
        assertFalse(isLongVideoDuration(59_000L)) // 59 s is out
        assertFalse(isLongVideoDuration(59_999L)) // just under the bar stays out
        assertTrue(isLongVideoDuration(60_000L)) // exactly 60 s is in
        assertTrue(isLongVideoDuration(60_001L))
        assertTrue(isLongVideoDuration(3_600_000L))
        assertFalse(isLongVideoDuration(0L))
        assertFalse(isLongVideoDuration(null)) // a photo has no duration and never qualifies
    }

    /** A UTC [Instant] at local noon, away from any DST or midnight boundary. */
    private fun utc(
        year: Int,
        month: Int,
        day: Int,
    ): Instant = LocalDateTime(year, month, day, 12, 0).toInstant(TimeZone.UTC)
}
