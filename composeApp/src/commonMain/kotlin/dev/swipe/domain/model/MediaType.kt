package dev.swipe.domain.model

/** The kinds of media a [MediaItem] can be (REQUIREMENTS §7). */
enum class MediaType {
    /** A still photo. */
    PHOTO,

    /** A video; autoplays muted and loops (FR-16). */
    VIDEO,

    /**
     * A Live Photo: a still with a paired short video. Long-press plays its motion (FR-17).
     * Counts as a photo for the All / Photos / Videos toggle (FR-5).
     */
    LIVE_PHOTO,
}
