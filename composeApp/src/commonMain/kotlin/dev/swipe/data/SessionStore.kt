package dev.swipe.data

import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SessionRecord

/**
 * Session bookkeeping (REQUIREMENTS §7 `session` / `session_skip`): the shuffle [seed]
 * and position that survive process death (§6.5 step 1, FR-20), and the per-session
 * skip list (FR-14). Timestamps use the injected `kotlin.time.Clock` — stable in the
 * Kotlin 2.4 stdlib, so no custom interface is defined here; `FakeClock` covers tests.
 */
interface SessionStore {
    /**
     * Start a session over [source] filtered by [typeFilter] with shuffle [seed];
     * returns the new session id (§6.5 step 1).
     */
    suspend fun create(
        source: DeckSource,
        typeFilter: MediaTypeFilter,
        seed: Long,
    ): String

    /** Save the deck position so a relaunch resumes at the same card (FR-20). */
    suspend fun updatePosition(
        id: String,
        position: Int,
    )

    /** Stamp `ended_at` when the deck runs out (FR-21, §7). */
    suspend fun end(id: String)

    /** The most recent session with no end time, for resume (FR-20); null if none. */
    suspend fun openSession(): SessionRecord?

    /** Assets skipped in this session; they stay excluded until it ends (FR-14). */
    suspend fun skippedIds(sessionId: String): Set<String>
}
