package dev.swipe.domain.model

/** What the summary screen shows when the deck runs out (FR-21, §6.6). */
data class SessionSummary(
    /** Items kept this session. */
    val kept: Int,
    /** Items queued for deletion this session. */
    val queued: Int,
    /** Items skipped this session (FR-14). */
    val skipped: Int,
)
