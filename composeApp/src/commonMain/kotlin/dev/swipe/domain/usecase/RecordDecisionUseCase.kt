package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.domain.model.Decision

/**
 * Persists one review verdict before the deck moves on (FR-12–14, NFR-7, §6.5 step 5).
 * The single job of this use case is the store write: no caching, no retry, no other
 * side effects. If the store throws, the failure is rethrown untouched so the caller
 * keeps the card in place and shows an error instead of advancing (NFR-7).
 */
class RecordDecisionUseCase(
    private val decisions: DecisionStore,
) {
    /**
     * Records [decision] for [id] in session [sessionId]. [Decision.SKIP] goes to
     * `session_skip` only and the item can return in a later session (FR-14, §7).
     */
    suspend operator fun invoke(
        id: String,
        decision: Decision,
        sessionId: String,
    ) {
        decisions.record(id, decision, sessionId)
    }
}
