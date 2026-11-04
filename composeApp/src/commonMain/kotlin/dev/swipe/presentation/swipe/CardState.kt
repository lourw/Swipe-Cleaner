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
    /**
     * Whether the full asset lives in iCloud (R6 cloud badge, WP-4.2) — the deck-time
     * [MediaItem.isCloudOnly] answer, so the badge can show before any pixel loads.
     * The card lights the cloud icon when this is true *or* the loaded image came
     * from the cloud ([dev.swipe.data.LoadedImage.isCloud]); either signal counts.
     */
    val isCloudOnly: Boolean = false,
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
                isCloudOnly = item.isCloudOnly == true,
            )
    }
}
