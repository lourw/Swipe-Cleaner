package dev.swipe.domain.model

import kotlin.time.Instant

/**
 * A review session, mirroring the `session` table (REQUIREMENTS §7). [seed] is kept so
 * the shuffle order survives process death (§6.5 step 1), and [position] lets a relaunch
 * resume at the same card (FR-20).
 */
data class SessionRecord(
    /** Generated session id. */
    val id: String,
    /** Where the deck came from, serialized as JSON in the row (§7). */
    val source: DeckSource,
    /** The media-type toggle in force when the session started (FR-5). */
    val typeFilter: MediaTypeFilter,
    /** Seeded-shuffle seed; the same seed rebuilds the same order (§6.5 step 1). */
    val seed: Long,
    /**
     * Cards this session has disposed (FR-20): a best-effort progress hint updated
     * after every move. Resume recomputes everything by rebuilding the deck from
     * [seed] and subtracting decided ids (§6.5 step 1), so the value itself is
     * never read back.
     */
    val position: Int,
    /** When the session started. */
    val startedAt: Instant,
    /** When the deck ran out or the session was closed; null while open (§7). */
    val endedAt: Instant?,
)
