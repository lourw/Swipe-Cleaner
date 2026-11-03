package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.domain.model.Decision

/**
 * Takes one item back out of the delete queue by recording `KEEP` for it (FR-24):
 * the upsert replaces the pending DELETE row, so the item leaves
 * [DecisionStore.deleteQueue] and, as a KEEP, it is in [DecisionStore.decidedIds]
 * and never reappears in a deck.
 *
 * The row is written under the synthetic [RESTORE_SESSION_ID]: the restore happens in
 * the queue screen, not in a review session, and §7's `session_id` column is plain
 * TEXT with no foreign key, so no schema change is needed. It therefore counts toward
 * no real session's [DecisionStore.sessionCounts] — correct, since it was not a swipe
 * in any of them — and rows the restore replaces were already leaving their session's
 * counts the moment they were queued (the resume-base caveat is tracked outside this WP).
 *
 * A failed write throws like every other [DecisionStore.record] (NFR-7's convention):
 * the item stays queued, and the caller surfaces the failure.
 */
class RestoreFromQueueUseCase(
    private val decisions: DecisionStore,
) {
    /** Records KEEP for [id] under [RESTORE_SESSION_ID] (FR-24). */
    suspend operator fun invoke(id: String) {
        decisions.record(id, Decision.KEEP, RESTORE_SESSION_ID)
    }

    companion object {
        /** Synthetic session id for restores; no real session ever uses it (§7). */
        const val RESTORE_SESSION_ID = "restore"
    }
}
