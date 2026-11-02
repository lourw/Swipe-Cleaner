package dev.swipe.domain.model

import kotlin.time.Instant

/**
 * The cheap fields the Likely Duplicates grouping needs per asset (FR-6), enumerated
 * without loading full metadata so a 50k library stays fast (NFR-4).
 * [dev.swipe.data.MediaLibrary.duplicateCandidates] returns these sorted by creation date
 * for `DuplicateGrouper` to group (§6.5).
 */
data class DuplicateKey(
    /** `PHAsset.localIdentifier`. */
    val id: String,
    /** Creation date; items within 2 s of each other can be duplicates (FR-6). */
    val createdAt: Instant?,
    /** Pixel width; duplicates share identical dimensions (FR-6). */
    val widthPx: Int,
    /** Pixel height; duplicates share identical dimensions (FR-6). */
    val heightPx: Int,
)
