package dev.swipe.data

/**
 * Warms the platform's playable-video item ahead of the deck (§6.5 step 4, FR-16):
 * the next video's `AVPlayerItem` is created as soon as its id enters the prefetch
 * window, so playback starts without a visible stall once its card is on top.
 * The iOS implementation (`platform/VideoItemCache.kt`) requests the item through
 * `PHImageManager.requestPlayerItemForVideo` with `networkAccessAllowed` per
 * [SettingsStore.includeCloudOnly], mirroring [MediaLoader]'s prefetch.
 *
 * Fire-and-forget like [MediaLoader.prefetch]: neither call throws at the call site,
 * so a failed read leaves the card on its poster frame instead of disturbing the
 * deck (NFR-7 is about decisions, not playback warm-up).
 */
interface VideoPreloader {
    /**
     * Warm the playable item for [id]. A no-op when the item is already warm. Callers
     * pass ids they know to be videos (`SwipeViewModel` filters the window by
     * `MediaItem.type`); a non-video id would only produce a failed request.
     */
    fun preload(id: String)

    /** Drop the warm item for [id] after it left the prefetch window (§6.5 step 3 parity). */
    fun cancel(id: String)
}
