package dev.swipe.data

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.Flow

/**
 * Loads pixels for an asset, decoded off the main thread (REQUIREMENTS §6.4, §6.2).
 * The PhotoKit implementation wraps `PHCachingImageManager` (WP-1.5).
 */
interface MediaLoader {
    /**
     * Emits a fast degraded image, then the full-quality one (NFR-1); closes after the
     * full-quality image. Cancelling the collector cancels the underlying
     * `PHImageRequestID` (§6.8).
     *
     * Each emission is a [LoadedImage]: the decoded [androidx.compose.ui.graphics.ImageBitmap]
     * plus the degraded / iCloud flags the card needs for the placeholder and cloud badge
     * (NFR-1, R6). [targetPx] is an upper bound; the loader caps it at the screen pixel
     * size so a card never asks for more pixels than it can show (NFR-2, R1).
     *
     * Caching: requests capped to the full screen share the loader's small decode
     * cache (NFR-3); smaller targets — grid tiles — bypass it entirely, so bulk
     * thumbnail fetches can never evict a visible card's bitmap (§6.8).
     */
    fun image(
        id: String,
        targetPx: IntSize,
    ): Flow<LoadedImage>

    /** Warm the cache for [ids] — the current plus next 3 items (§6.5 step 3). */
    fun prefetch(
        ids: List<String>,
        targetPx: IntSize,
    )

    /**
     * Drop cache work for [ids] that left the prefetch window (§6.5 step 3). [targetPx]
     * must match the [prefetch] call it cancels — PhotoKit only cancels requests made at
     * the same target size.
     */
    fun cancelPrefetch(
        ids: List<String>,
        targetPx: IntSize,
    )

    companion object {
        /**
         * The full-display request: an unbounded [image] / [prefetch] target the loader
         * caps at the screen's pixel size (NFR-2, R1). Cards and the ViewModel's prefetch
         * share this one value so both resolve to the same cache key — a prefetched decode
         * is served to the card without a second PhotoKit round-trip (NFR-1, NFR-3).
         */
        val FULL_TARGET_PX: IntSize = IntSize(Int.MAX_VALUE, Int.MAX_VALUE)
    }
}
