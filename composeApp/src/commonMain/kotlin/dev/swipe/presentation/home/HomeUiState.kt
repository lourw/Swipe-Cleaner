package dev.swipe.presentation.home

import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.model.Stats

/**
 * The one state of [HomeViewModel] (FR-4, FR-5, FR-9, FR-10, §5.8 Home): the type
 * toggle, the source rows with their progressively-loaded counts, the lifetime
 * stats line, the To Delete queue size, and the access signal behind the FR-2
 * banner.
 *
 * **NFR-4 placeholder rule:** every count on this screen is nullable or absent
 * until its source answers, and the screen renders a placeholder (an em dash) for
 * a null count — Home is interactive before any count arrives. A refresh replaces
 * arrived counts in place; it never dips them back to the placeholder.
 */
data class HomeUiState(
    /** The selected media-type toggle, applied on top of every source (FR-5). */
    val typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    /**
     * The **Shuffle everything** row count (FR-4): null until the ids-only fetch
     * answers; PhotoKit's raw size first, then the exact FR-9 count (§6.5
     * *Home counts*).
     */
    val shuffleCount: Int? = null,
    /**
     * The five **SMART FILTERS** rows in §5.8 order (the [SmartFilter] declaration
     * order): always five rows so the screen renders immediately, each count null
     * — placeholder — until its raw count and then its exact FR-9 count arrive
     * (§6.5 *Home counts*). The [SmartFilter] → title mapping is the screen's
     * (WP-5.3).
     */
    val smartRows: List<HomeSmartRow> = SmartFilter.entries.map { HomeSmartRow(it, null) },
    /**
     * The album rows for the **Albums** screen (FR-8): empty until `albums()`
     * answers, then PhotoKit-estimate counts converging to the exact FR-9 counts
     * for [typeFilter] (§6.5 *Home counts*). A filter switch keeps the rows but
     * drops their counts to the placeholder — never stale numbers under a new
     * label (FR-5). [coverAssetId] drives the 40 dp row thumbnail (WP-5.3).
     */
    val albumRows: List<HomeAlbumRow> = emptyList(),
    /** Lifetime reviewed / kept / deleted for the stats line (FR-10); null before the first read. */
    val stats: Stats? = null,
    /** Items waiting in the To Delete queue (FR-10); the queue Banner shows iff > 0. */
    val queueCount: Int = 0,
    /**
     * The most recent authorization read — the limited-banner signal (FR-2); null
     * before the first read. The banner's item count itself stays with
     * [dev.swipe.presentation.permission.PermissionViewModel].
     */
    val access: LibraryAccess? = null,
)

/**
 * One **SMART FILTERS** row (FR-6, §5.8): the [kind] the row opens as
 * `DeckSource.Smart(kind)`, and its count — null until loaded (NFR-4's
 * placeholder).
 */
data class HomeSmartRow(
    /** Which smart filter this row is (FR-6). */
    val kind: SmartFilter,
    /** Item count excluding decided items (FR-9); null while loading. */
    val count: Int?,
)

/**
 * One **Albums** row (FR-8, §5.8): [id] + [title] open it as
 * `DeckSource.Album(id, title)`, [coverAssetId] drives the 40 dp thumbnail, and
 * [count] is null until loaded (NFR-4's placeholder), PhotoKit's estimate first
 * and then the exact FR-9 count for the current filter (§6.5 *Home counts*).
 */
data class HomeAlbumRow(
    /** Stable PhotoKit collection identifier. */
    val id: String,
    /** Album title shown on the row (FR-8). */
    val title: String,
    /** Newest asset's id for the cover thumbnail; null when the album is empty. */
    val coverAssetId: String?,
    /** Item count excluding decided items (FR-9); null while loading. */
    val count: Int?,
)
