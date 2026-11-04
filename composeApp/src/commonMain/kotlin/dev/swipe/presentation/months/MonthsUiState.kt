package dev.swipe.presentation.months

import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket

/**
 * The one state of [MonthsViewModel] (FR-7): the month buckets for
 * [typeFilter], newest first as [dev.swipe.data.MediaLibrary.months] returns
 * them — the year grouping is the screen's (§5.8: year sections → month
 * rows). Empty until the scan answers, which runs only while this screen is
 * open (NFR-4, FR-7); the screen shows no counts before that.
 */
data class MonthsUiState(
    /** The media-type filter carried from Home (FR-5); applied to the scan and decks. */
    val typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    /** Calendar-month buckets, newest first (FR-7). */
    val buckets: List<MonthBucket> = emptyList(),
)
