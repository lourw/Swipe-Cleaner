package dev.swipe.data

import dev.swipe.domain.model.MonthBucket
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Sampling resolution for [SourceQueriesTest]'s DST agreement walk (FR-7). */
private val dstProbeStep = 15.minutes

/**
 * WP-5.1 pure helpers behind the source queries (FR-6, FR-7), covered without PhotoKit (§8.2).
 * All calendar checks pin [TimeZone.UTC] for determinism; the real library passes the device
 * zone, and the shared logic is what these tests protect.
 */
class SourceQueriesTest {
    @Test
    fun `FR-7 month range and membership agree across DST`() {
        // The PhotoKit deck predicate compares absolute instants against [monthRange]'s
        // bounds while Kotlin-side membership is [instantInMonth]; a bucket and the deck
        // opened from it must not disagree at DST transitions (FR-7). Santiago folds its
        // clocks mid-day in April and September — sampled at 15 min steps, every instant
        // falls inside exactly its own local month's range, and no other month's.
        val zone = TimeZone.of("America/Santiago")
        var instant = Instant.parse("2024-01-01T00:00:00Z")
        val endOfProbe = Instant.parse("2025-01-01T00:00:00Z")
        while (instant < endOfProbe) {
            val local = instant.toLocalDateTime(zone)
            val month = local.month.ordinal + 1
            val (start, end) = monthRange(local.year, month, zone)
            assertTrue(instant >= start && instant < end, "$instant must fall in its own month range")
            assertTrue(instantInMonth(instant, local.year, month, zone), "$instant must be a member of its own month")
            val otherMonth = if (month == 12) 1 else month + 1
            assertFalse(instantInMonth(instant, local.year, otherMonth, zone), "$instant must not join the next month")
            instant += dstProbeStep
        }
    }

    @Test
    fun `FR-7 month buckets are newest first with counts`() {
        // Two March 2026, one January 2026, three December 2025, plus an undated item that
        // belongs to no bucket (FR-7). Seeded January-before-March on purpose: if the month
        // tiebreak in the sort ever regresses to year-only, the stable group order comes out
        // 2026-01 before 2026-03 and this test fails (it survived exactly that mutation once).
        val dates =
            listOf(
                utc(2026, 1, 15),
                utc(2025, 12, 1),
                utc(2026, 3, 4),
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
