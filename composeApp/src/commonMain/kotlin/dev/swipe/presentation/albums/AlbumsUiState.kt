package dev.swipe.presentation.albums

import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.home.HomeAlbumRow

/**
 * The one state of [AlbumsViewModel] (FR-8, §6.5 *Home counts*): the albums
 * with their covers and counts. The rows appear with PhotoKit's cheap
 * estimate (FR-8, NFR-4) and each converges to the exact FR-9 count for
 * [typeFilter] as the refinement wave lands.
 */
data class AlbumsUiState(
    /** The media-type filter carried from Home (FR-5); applied to every count and deck. */
    val typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    /** The album rows (FR-8): user albums first, then the main smart albums. */
    val rows: List<HomeAlbumRow> = emptyList(),
)
