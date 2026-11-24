package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.data.SessionStore
import dev.swipe.domain.model.SessionSummary
import kotlinx.coroutines.CancellationException

/**
 * FR-21: settle a session whose deck ran out (or opened empty) — build the summary
 * from the stored rows, so the counts cover the whole session across process
 * death, and stamp the session row ended. Extracted from `SwipeViewModel` by
 * WP-9.2; the caller keeps publishing the state.
 */
class FinishSessionUseCase(
    private val decisions: DecisionStore,
    private val sessionStore: SessionStore,
) {
    /**
     * The session summary, or null when the count query fails — then the row
     * stays open so resuming an exhausted session retries the summary (the card
     * is already disposed, so the run is over either way; only the summary and
     * the end stamp are pending). On success the row is ended best effort: an
     * un-stamped end leaves the row open, and a resumed exhausted deck simply
     * rebuilds empty — no decision is at stake.
     */
    suspend operator fun invoke(sessionId: String): SessionSummary? {
        val summary =
            try {
                val counts = decisions.sessionCounts(sessionId)
                SessionSummary(
                    kept = counts.kept,
                    queued = counts.queued,
                    skipped = sessionStore.skippedIds(sessionId).size,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        if (summary != null) {
            endQuietly(sessionId)
        }
        return summary
    }

    /**
     * End a session whose deck opened empty (FR-21, WP-7.3): nothing to resume,
     * so the row ends right away instead of staying open forever. Best effort —
     * a failed end leaves the row open, and the next open resumes the
     * still-empty, still-unreviewed deck and closes it again.
     */
    suspend fun endEmptyDeck(sessionId: String) {
        endQuietly(sessionId)
    }

    private suspend fun endQuietly(sessionId: String) {
        try {
            sessionStore.end(sessionId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Ignored on purpose; see above.
        }
    }
}
