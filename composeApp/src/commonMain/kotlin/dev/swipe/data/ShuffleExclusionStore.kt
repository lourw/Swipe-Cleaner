package dev.swipe.data

import kotlinx.coroutines.flow.Flow

/**
 * The albums the user excludes from **Shuffle everything** (FR-37, §6.4). One
 * row per excluded album id in the local `shuffle_exclusion` table (§7); the
 * exclusion applies to the Shuffle source only — opening an excluded album,
 * By month, and the smart filters still include its items (FR-37).
 *
 * The store keeps album ids, never asset ids: membership is resolved against
 * the live library at deck-build time, so a deleted album id is simply ignored
 * and items a user moves into an excluded album join the exclusion on the next
 * deck.
 */
interface ShuffleExclusionStore {
    /** The excluded album ids, empty when nothing is excluded (FR-37). */
    suspend fun excludedAlbumIds(): Set<String>

    /** The excluded ids as a live Flow, for the screens that show or apply them. */
    fun excludedAlbumIdsFlow(): Flow<Set<String>>

    /** Exclude [albumId] from Shuffle, or include it again when false (FR-37). */
    suspend fun setExcluded(
        albumId: String,
        excluded: Boolean,
    )
}
