package dev.swipe.presentation.months

import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket
import dev.swipe.presentation.ListStatus

/**
 * The one state of [MonthsViewModel] (FR-7, FR-9, §5.8): where the
 * creation-date scan stands ([status], WP-9.6) and, once it has answered,
 * the month rows for [typeFilter], newest first as
 * [dev.swipe.data.MediaLibrary.months] returns them — the year grouping is
 * the screen's (§5.8: year sections → month rows). The scan runs only while
 * this screen is open (NFR-4, FR-7); each row's count is null — the
 * placeholder — until its exact FR-9 deck size lands.
 */
data class MonthsUiState(
    /** The media-type filter carried from Home (FR-5); applied to the scan and decks. */
    val typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    /**
     * Loading / ready / empty / error for the month list (WP-9.6, FR-7):
     * the screen opens [ListStatus.Loading] while the scan runs, a scan that
     * fails with nothing on screen explains itself as [ListStatus.Error]
     * with a retry, and a failed *refresh* keeps the rows shown (§6.8).
     */
    val status: ListStatus = ListStatus.Loading,
    /** Calendar-month rows, newest first (FR-7), each with its FR-9-exact count. */
    val rows: List<MonthRow> = emptyList(),
)

/**
 * One month row (FR-7): the [bucket] it opens as `DeckSource.Month`, and
 * [count] — the size of that very deck, decided items subtracted (FR-9);
 * null while loading (NFR-4's placeholder).
 */
data class MonthRow(
    /** The calendar bucket this row opens. */
    val bucket: MonthBucket,
    /** Undecided items created in the bucket (FR-9); null while loading. */
    val count: Int? = null,
)
