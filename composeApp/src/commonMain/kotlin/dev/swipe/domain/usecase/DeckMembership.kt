package dev.swipe.domain.usecase

import dev.swipe.data.MediaLibrary
import dev.swipe.data.ShuffleExclusionStore
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter

/**
 * The one deck-membership function (FR-9, FR-37, §6.5): a source's asset ids
 * minus the decided items, and — for [DeckSource.All] only — minus every item
 * that lives in any album the user excluded from Shuffle. The deck builder, the
 * Home counts, and the Albums list all count through here, so a displayed count
 * and the deck its row opens are the same query by construction (FR-9).
 *
 * FR-37 scope: an excluded album's items leave only the Shuffle source; opening
 * the album directly, By month, and the smart filters keep them. Membership is
 * resolved against the live library at call time, so a deleted album's stale id
 * matches nothing and is ignored (FR-37). An item in both an excluded and a
 * non-excluded album still leaves the Shuffle — exclusion follows the album
 * membership, not the album the user opened.
 *
 * Only ids move here, never metadata (NFR-4): each read is
 * [MediaLibrary.assetIds], ids-only.
 */
class DeckMembership(
    private val library: MediaLibrary,
    private val exclusions: ShuffleExclusionStore,
) {
    /**
     * [source]'s ids for [type] with [decided] subtracted, and for
     * [DeckSource.All] also the items of every excluded album subtracted
     * (FR-37). Every other source is unaffected by exclusions.
     */
    suspend fun ids(
        source: DeckSource,
        type: MediaTypeFilter,
        decided: Set<String>,
    ): List<String> {
        val ids = library.assetIds(source, type).filterNot { it in decided }
        if (source != DeckSource.All) return ids
        val excludedAlbums = exclusions.excludedAlbumIds()
        if (excludedAlbums.isEmpty()) return ids
        val excludedItems = mutableSetOf<String>()
        excludedAlbums.forEach { albumId ->
            // The title is unused by id-based membership; a deleted album
            // answers empty, which is exactly "ignored" (FR-37).
            excludedItems += library.assetIds(DeckSource.Album(albumId, title = ""), type)
        }
        return ids.filterNot { it in excludedItems }
    }

    /**
     * The Likely Duplicates deck order (FR-6): the cheap [MediaLibrary.duplicateCandidates]
     * pass with [decided] subtracted **before** [DuplicateGrouper.group], so a twin
     * whose partner is decided leaves the deck — membership-minus-decided would
     * over-report it. The one path shared by the deck and the Home count.
     */
    suspend fun duplicateDeckIds(
        type: MediaTypeFilter,
        decided: Set<String>,
    ): List<String> =
        DuplicateGrouper.group(
            library
                .duplicateCandidates(type)
                .filterNot { it.id in decided },
        )
}
