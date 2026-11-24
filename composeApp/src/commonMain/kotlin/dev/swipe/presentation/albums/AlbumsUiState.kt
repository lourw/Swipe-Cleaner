package dev.swipe.presentation.albums

import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.ListStatus
import dev.swipe.presentation.home.HomeAlbumRow

/**
 * The one state of [AlbumsViewModel] (FR-8, §5.8, §6.5 *Home counts*): where
 * the album scan stands ([status], WP-9.6) and, once it has answered, the
 * albums with their covers and counts — each row converging to the exact FR-9
 * count for [typeFilter] as the counts land.
 */
data class AlbumsUiState(
    /** The media-type filter carried from Home (FR-5); applied to every count and deck. */
    val typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    /**
     * Loading / ready / empty / error for the album list (WP-9.6, FR-8):
     * the screen opens [ListStatus.Loading], a failed scan that left nothing
     * on screen explains itself as [ListStatus.Error] with a retry, and a
     * failed *refresh* keeps the rows shown (§6.8).
     */
    val status: ListStatus = ListStatus.Loading,
    /** The album rows (FR-8): user albums first, then the main smart albums. */
    val rows: List<HomeAlbumRow> = emptyList(),
    /**
     * The ids of the albums excluded from Shuffle (FR-37); drives each row's
     * switch. Empty by default — nothing is excluded until the user says so.
     */
    val excludedAlbumIds: Set<String> = emptySet(),
)
