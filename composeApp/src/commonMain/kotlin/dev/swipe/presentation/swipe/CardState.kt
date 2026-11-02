package dev.swipe.presentation.swipe

import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import kotlin.time.Instant

/**
 * Everything one card shows (§6.6 `current` / `next`): the loaded metadata of a single
 * asset (§6.5 step 2). Pixels are deliberately absent — the card composable collects
 * [dev.swipe.data.MediaLoader.image] itself (WP-1.9), so no bitmap lives in the state.
 */
data class CardState(
    /** Asset id; keys the image request and the decision record (FR-12–14). */
    val id: String,
    /** Still, video, or Live Photo; drives what the card renders (FR-16–18). */
    val type: MediaType,
    /** Creation date for the metadata row (FR-18). */
    val createdAt: Instant?,
    /** Playback length in milliseconds, videos only (FR-18). */
    val durationMs: Long?,
    /** `PHAsset.isFavorite`; the delete confirm checks it (FR-22, WP-2.2). */
    val isFavorite: Boolean,
) {
    companion object {
        /** Projects one loaded [MediaItem] onto a card (§6.5 step 2). */
        fun from(item: MediaItem): CardState =
            CardState(
                id = item.id,
                type = item.type,
                createdAt = item.createdAt,
                durationMs = item.durationMs,
                isFavorite = item.isFavorite,
            )
    }
}
