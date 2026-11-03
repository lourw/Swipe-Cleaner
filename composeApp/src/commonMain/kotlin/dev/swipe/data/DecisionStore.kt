package dev.swipe.data

import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.SessionCounts
import dev.swipe.domain.model.Stats
import kotlinx.coroutines.flow.Flow

/**
 * Durable record of every swipe decision (REQUIREMENTS §6.4, §7). Implemented on
 * SQLDelight (WP-1.3), faked in `commonTest/fakes/`. Writes throw on failure so the
 * caller can revert the card (NFR-7).
 */
interface DecisionStore {
    /**
     * Persist a decision before the UI advances (FR-20, NFR-7). A failed write throws
     * so nothing is lost and the swipe is reverted (NFR-7).
     *
     * [Decision.SKIP] goes to `session_skip` for [sessionId] and never into the
     * `decision` table (§7 comment, FR-14); every other decision is stored per asset.
     */
    suspend fun record(
        id: String,
        decision: Decision,
        sessionId: String,
    )

    /**
     * Undo (FR-15): removes the row that holds [id] — its `decision` row, or its
     * `session_skip` row *for [sessionId]* when the last decision was a SKIP. The
     * session narrows the skip side so undoing a skip here never clears the same
     * asset's skip row in another session (the resume flow, §6.5 step 1).
     */
    suspend fun remove(
        id: String,
        sessionId: String,
    )

    /** Ids with a KEEP or a pending DELETE, which every source subtracts (FR-9). */
    suspend fun decidedIds(): Set<String>

    /**
     * This session's KEEP / pending-DELETE counts, computed with a `COUNT` query
     * (§7): the summary numbers (FR-21) and the resume index correction, since a
     * rebuilt deck subtracts exactly these rows (§6.5 step 1).
     */
    suspend fun sessionCounts(sessionId: String): SessionCounts

    /**
     * Pending DELETEs, newest decision first (FR-23), re-emitted whenever the queue
     * changes so the badge and queue screen stay live.
     */
    fun deleteQueue(): Flow<List<String>>

    /** Mark [ids] DELETED after the user confirmed in the iOS dialog (FR-26). */
    suspend fun markDeleted(ids: List<String>)

    /** Drop KEEP rows only — the delete queue stays untouched (FR-30). */
    suspend fun clearKeeps()

    /**
     * Lifetime counts for Home, computed from the `decision` table with `COUNT`
     * queries and re-emitted on change (FR-10, §7).
     */
    fun stats(): Flow<Stats>
}
