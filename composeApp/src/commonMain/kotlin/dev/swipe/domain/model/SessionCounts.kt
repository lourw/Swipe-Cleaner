package dev.swipe.domain.model

/**
 * KEEP / DELETE rows belonging to one session (REQUIREMENTS §6.4, `DecisionStore.sessionCounts`).
 * Backs the session summary counts (FR-21) and the resume reviewed base: after a resume the
 * rebuilt deck has already dropped these rows, but they still count toward `reviewed` (§6.5 step 1).
 */
data class SessionCounts(
    /** Items kept in this session. */
    val kept: Int,
    /** Items queued for deletion in this session (pending DELETE rows). */
    val queued: Int,
)
