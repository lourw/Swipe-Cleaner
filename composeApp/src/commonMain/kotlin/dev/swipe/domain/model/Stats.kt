package dev.swipe.domain.model

/**
 * Lifetime counts for Home (FR-10). Computed with `COUNT` queries over the
 * `decision` table; nothing is stored separately (§7).
 */
data class Stats(
    /** Every item ever decided: KEEP + pending DELETE + DELETED (FR-10). */
    val reviewed: Int,
    /** Items kept (FR-10). */
    val kept: Int,
    /** Items actually deleted after a confirmed batch (FR-10). */
    val deleted: Int,
    /** Items still waiting in the To Delete queue (FR-10). */
    val queued: Int,
)
