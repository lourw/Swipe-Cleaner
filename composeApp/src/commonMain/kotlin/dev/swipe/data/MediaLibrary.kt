package dev.swipe.data

import dev.swipe.domain.model.AlbumSource
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DuplicateKey
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket
import kotlinx.coroutines.flow.Flow

/**
 * The photo library's **read side** (REQUIREMENTS §6.4): authorization, sources, ids,
 * metadata, and change notifications through PhotoKit. Implemented in
 * `iosMain/platform/`, faked in `commonTest/fakes/` (§8.2). Fetches run on
 * `Dispatchers.Default` (§6.8).
 *
 * Deleting is **not** on this interface (WP-9.1, NFR-5): it lives on [MediaDeleter],
 * which only `CommitDeletionsUseCase` is given (AGENTS.md rule 1). Everything that
 * reads the library — every ViewModel — takes this type and therefore cannot express
 * a delete at all.
 */
interface MediaLibrary {
    /** The current permission state without prompting the user (FR-1, FR-2, FR-3). */
    suspend fun authorization(): LibraryAccess

    /** Ask the system for `readWrite` access and return the outcome (FR-1). */
    suspend fun requestAuthorization(): LibraryAccess

    /**
     * The **Albums** rows (FR-4, FR-8): user albums plus the main smart albums (Favorites,
     * Recently Added, Videos, Selfies, Screenshots, Live Photos, Bursts), each with a cheap
     * count and a cover asset id. No per-asset enumeration (NFR-4), so this is safe at Home
     * startup; ordering is user albums first, then the smart albums.
     */
    suspend fun albums(): List<AlbumSource>

    /**
     * The **By month** buckets for [type] (FR-5, FR-7): calendar months of the device zone
     * with counts, newest first. This enumerates every asset's creation date, so call it
     * lazily when the By-month screen opens — **never at Home startup** (NFR-4).
     */
    suspend fun months(type: MediaTypeFilter): List<MonthBucket>

    /**
     * Asset IDs of [source] narrowed by [type] (FR-5). IDs only, no metadata, so this
     * stays fast on a 50k-item library (NFR-4). The order is deck order (§6.5):
     * `Smart(LONG_VIDEOS)` comes back duration-descending, every other source
     * creation-date descending.
     */
    suspend fun assetIds(
        source: DeckSource,
        type: MediaTypeFilter,
    ): List<String>

    /**
     * How many assets the app can see, the FR-2 limited-banner N: the size of the
     * same library-wide fetch [assetIds] (`DeckSource.All`, `MediaTypeFilter.ALL`)
     * reads, taken from the fetch result's count with no enumeration (NFR-4).
     */
    suspend fun assetCount(): Int

    /** Metadata for one page of at most 20 ids, kept in the order given (§6.5 step 2, NFR-4). */
    suspend fun items(ids: List<String>): List<MediaItem>

    /**
     * Emits whenever the library changes elsewhere, so Home refreshes counts and the
     * queue prunes vanished ids (FR-28, §6.8 `PHPhotoLibraryChangeObserver`).
     */
    fun changes(): Flow<Unit>

    /**
     * Cheap candidates for the Likely Duplicates filter (FR-6): id, creation date, and
     * pixel dimensions of every item passing [type], sorted by creation date. Feeds
     * `DuplicateGrouper` without loading full metadata (§6.5, NFR-4).
     */
    suspend fun duplicateCandidates(type: MediaTypeFilter): List<DuplicateKey>
}
