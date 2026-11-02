package dev.swipe.domain.model

/** The five smart filters of v1 (FR-6). */
enum class SmartFilter {
    /** Assets with `PHAssetMediaSubtype.photoScreenshot` (FR-6). */
    SCREENSHOTS,

    /** Videos of 60 s or longer, longest first (FR-6). */
    LONG_VIDEOS,

    /** Assets that have a `burstIdentifier` (FR-6). */
    BURSTS,

    /**
     * Items taken within 2 s of each other with identical pixel dimensions,
     * shown next to each other (FR-6, §6.5).
     */
    LIKELY_DUPLICATES,

    /** The `smartAlbumSelfPortraits` collection (FR-6). */
    SELFIES,
}
