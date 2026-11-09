package dev.swipe.domain.model

/** Outcome of preparing one item for the iOS share sheet (FR-32). */
enum class ShareResult {
    /**
     * The original media loaded and the share sheet is on screen. What the user then
     * does with it (share, cancel) is not reported back: it never touches the deck.
     */
    PRESENTED,

    /**
     * The media could not be loaded (deleted asset, iCloud download refused or failed,
     * no presentable window); nothing was shown and a toast says so.
     */
    FAILED,
}
