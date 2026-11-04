package dev.swipe.domain.model

/**
 * One calendar-month bucket for **By month** (FR-4, FR-7): [year] and 1-based [month] with
 * the number of matching items. Newest month first. Produced lazily by
 * [dev.swipe.data.MediaLibrary.months] when the By-month screen opens and never enumerated
 * at Home startup (NFR-4). The calendar zone is the device's, matching how a card shows its
 * date (§5.7); see [dev.swipe.data.monthBuckets].
 */
data class MonthBucket(
    /** Proleptic Gregorian year, e.g. 2026. */
    val year: Int,
    /** Month of year, 1..12. */
    val month: Int,
    /** Items created in this month; items with no creation date are in no month (FR-7). */
    val count: Int,
)
