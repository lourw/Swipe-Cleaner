package dev.swipe.domain.model

/**
 * The outcome of reviewing one asset (REQUIREMENTS §7). Stored per asset in the
 * `decision` table, except [SKIP], which goes to `session_skip` (§7 comment).
 */
enum class Decision {
    /** Right swipe / Keep button (FR-12). */
    KEEP,

    /** Left swipe / Delete button: queued, not yet deleted (FR-13). */
    DELETE,

    /** Up swipe / Skip button: excluded for this session only (FR-14). */
    SKIP,

    /** Confirmed gone from the library via the iOS dialog (FR-26). */
    DELETED,
}
