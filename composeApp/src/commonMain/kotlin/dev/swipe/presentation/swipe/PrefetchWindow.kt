package dev.swipe.presentation.swipe

import dev.swipe.data.MediaLoader
import dev.swipe.data.VideoPreloader
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType

/**
 * The §6.5 steps 3–4 prefetch windows: after every deck move the window of
 * current + next 3 is requested from [MediaLoader.prefetch], and ids that left
 * the window are cancelled. Both calls pass the identical target size —
 * PhotoKit only cancels prefetches made at the same size (§6.4).
 *
 * §6.5 step 4: the window's videos are pre-playlisted through [VideoPreloader]
 * (FR-16); ids that left the window are released again. `preload` is idempotent
 * for warm ids, so re-entering a window never re-requests (the cache dedupes).
 * Live Photos are deliberately absent — their paired video is exported on mount
 * (FR-17), not ahead of the card.
 */
class PrefetchWindow(
    private val mediaLoader: MediaLoader,
    private val videoPreloader: VideoPreloader,
) {
    private var images: List<String> = emptyList()
    private var videos: List<String> = emptyList()

    /**
     * Re-point both windows at the slice of [deck] starting at [index], given
     * the paged [metadata] (only ids known to be videos are pre-playlisted).
     */
    fun refresh(
        deck: List<String>,
        index: Int,
        metadata: Map<String, MediaItem>,
    ) {
        val window =
            (index until index + WINDOW)
                .mapNotNull { deck.getOrNull(it) }
        val stale = images.filterNot { it in window }
        if (stale.isNotEmpty()) {
            mediaLoader.cancelPrefetch(stale, TARGET_PX)
        }
        if (window.isNotEmpty()) {
            mediaLoader.prefetch(window, TARGET_PX)
        }
        images = window
        val videoIds = window.filter { metadata[it]?.type == MediaType.VIDEO }
        videos.filterNot { it in videoIds }.forEach(videoPreloader::cancel)
        videoIds.filterNot { it in videos }.forEach(videoPreloader::preload)
        videos = videoIds
    }

    /** Leaving the deck (ViewModel cleared) cancels the still-warm windows. */
    fun release() {
        if (images.isNotEmpty()) {
            mediaLoader.cancelPrefetch(images, TARGET_PX)
            images = emptyList()
        }
        if (videos.isNotEmpty()) {
            videos.forEach(videoPreloader::cancel)
            videos = emptyList()
        }
    }

    companion object {
        /** §6.5 step 3: prefetch current + next 3. */
        const val WINDOW = 4

        /**
         * Prefetch asks for the screen's pixel size (§6.5 step 3); the loader caps
         * every request at the screen's pixel size (NFR-2, R1), so an unbounded
         * request resolves to exactly that. [MediaLoader.cancelPrefetch] must pass
         * the identical value, and WP-1.9's cards request the same
         * [MediaLoader.FULL_TARGET_PX] so a prefetched decode lands in the card's
         * cache key — one shared constant on the interface.
         */
        val TARGET_PX = MediaLoader.FULL_TARGET_PX
    }
}
