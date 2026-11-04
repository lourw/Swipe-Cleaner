package dev.swipe.data

import dev.swipe.domain.model.MonthBucket
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * Pure helpers behind the WP-5.1 source queries (FR-6, FR-7): the calendar-month bucketing,
 * the month-membership test, the half-open month range that becomes the PhotoKit `creationDate`
 * predicate, and the FR-6 long-video threshold. They live in commonMain so commonTest covers
 * them without PhotoKit (§8.2) and so the fake and the real library share one implementation and
 * cannot drift.
 *
 * **Calendar zone.** Every calculation takes an explicit [TimeZone] rather than hiding one. The
 * PhotoKit library passes the device zone (`TimeZone.currentSystemDefault()`), which is what a
 * card shows as its date (§5.7) and how the iOS Photos app groups months, so a "March" bucket
 * and the deck opened from it agree with what the user sees. The fake injects a fixed zone for
 * deterministic tests. `monthBuckets`, `instantInMonth` and `monthRange` are all driven by the
 * same zone, so the range predicate and the bucket it came from cannot disagree by construction.
 */

/** FR-6: a long video is 60 s or longer — inclusive, so exactly 60 s counts and 59.999 s does not. */
const val LONG_VIDEO_MIN_MS: Long = 60_000L

/** Whether a duration in milliseconds clears the FR-6 long-video bar (inclusive at 60 s). */
fun isLongVideoDuration(durationMs: Long?): Boolean = (durationMs ?: 0L) >= LONG_VIDEO_MIN_MS

/**
 * Bucket [dates] into calendar months in [zone], newest month first (FR-7). Null dates belong to
 * no month and are dropped; months with no items never appear.
 */
fun monthBuckets(
    dates: List<Instant?>,
    zone: TimeZone,
): List<MonthBucket> =
    dates
        .mapNotNull { date -> date?.let { it.toLocalDateTime(zone) to it } }
        .groupBy { (local, _) -> local.year to local.month.ordinal + 1 }
        .map { (key, entries) -> MonthBucket(year = key.first, month = key.second, count = entries.size) }
        .sortedWith(compareByDescending<MonthBucket> { it.year }.thenByDescending { it.month })

/**
 * Whether [instant] falls in the [year]-[month] bucket in [zone] (FR-7). Null dates are in no
 * month. This is the Kotlin-side month test the fake uses for [dev.swipe.domain.model.DeckSource.Month]
 * decks; the real library expresses the same membership as the [monthRange] predicate.
 */
fun instantInMonth(
    instant: Instant?,
    year: Int,
    month: Int,
    zone: TimeZone,
): Boolean {
    if (instant == null) return false
    val local = instant.toLocalDateTime(zone)
    return local.year == year && local.month.ordinal + 1 == month
}

/**
 * The half-open `[start, end)` [Instant] range covering [year]-[month] in [zone] — the bounds of
 * the PhotoKit `creationDate` range predicate for a month deck (FR-7). Built from the same [zone]
 * as [monthBuckets] / [instantInMonth], so a range query and the bucket it came from agree on
 * membership by construction.
 */
fun monthRange(
    year: Int,
    month: Int,
    zone: TimeZone,
): Pair<Instant, Instant> {
    val start = LocalDateTime(year, month, 1, 0, 0).toInstant(zone)
    val nextMonthStart =
        if (month == 12) {
            LocalDateTime(year + 1, 1, 1, 0, 0)
        } else {
            LocalDateTime(year, month + 1, 1, 0, 0)
        }
    return start to nextMonthStart.toInstant(zone)
}
