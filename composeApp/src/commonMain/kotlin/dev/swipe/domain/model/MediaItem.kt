package dev.swipe.domain.model

import kotlin.time.Instant

/**
 * One photo or video in the user's library (REQUIREMENTS §7). This is the metadata
 * a card shows or a filter needs (FR-18); the pixels themselves come from [dev.swipe.data.MediaLoader].
 */
data class MediaItem(
    /** `PHAsset.localIdentifier` (§7). */
    val id: String,
    /** Still, video, or Live Photo (FR-16, FR-17). */
    val type: MediaType,
    /** Creation date, or null when PhotoKit doesn't know it. Month buckets ignore nulls (FR-7). */
    val createdAt: Instant?,
    /** Playback length in milliseconds; videos only (FR-18, FR-6 long videos). */
    val durationMs: Long?,
    /** Pixel width; used by the duplicate grouping (FR-6). */
    val widthPx: Int,
    /** Pixel height; used by the duplicate grouping (FR-6). */
    val heightPx: Int,
    /** `PHAsset.isFavorite`; a left swipe asks first (FR-22). */
    val isFavorite: Boolean,
    /** `PHAssetMediaSubtype.photoScreenshot`; drives the Screenshots filter (FR-6). */
    val isScreenshot: Boolean,
    /** `PHAsset.burstIdentifier`; null unless the asset belongs to a burst (FR-6). */
    val burstId: String?,
    /** True when the asset lives only in iCloud. Null until a load is attempted (§7, R6). */
    val isCloudOnly: Boolean?,
)
