package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore

/**
 * Removes the stored verdict for one asset so the card can come back (FR-15, §6.4).
 * Clears whichever row holds the id — a `decision` row or the [sessionId] session's
 * skip row — via [DecisionStore.remove]. The caller (the undo stack in `SwipeViewModel`,
 * WP-2.2) decides the order relative to the index move: remove first, then move back.
 */
class UndoDecisionUseCase(
    private val decisions: DecisionStore,
) {
    /**
     * Clears the persisted decision, or this session's skip, for [id] (FR-15). The
     * [sessionId] scopes the skip side so undo never touches another session (FR-14).
     */
    suspend operator fun invoke(
        id: String,
        sessionId: String,
    ) {
        decisions.remove(id, sessionId)
    }
}
