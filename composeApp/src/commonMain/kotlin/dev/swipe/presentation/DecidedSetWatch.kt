package dev.swipe.presentation

import dev.swipe.data.DecisionStore
import kotlinx.coroutines.CancellationException

/**
 * FR-30, §6.5 Counts. One deduplicated trigger for every decision change: a
 * screen calls [changedSinceLastCheck] when it resumes and re-runs its counts
 * only when the decided set actually moved (a reset, a finished deck, a
 * commit, a queue edit), so a no-change resume costs nothing. Shared by Home,
 * Albums and By-month; depends only on the [DecisionStore] interface
 * (hard rule 5 — no platform access).
 */
internal class DecidedSetWatch(
    private val decisions: DecisionStore,
) {
    private var lastSeen: Set<String>? = null

    /**
     * True when `decisions.decidedIds()` differs from the set seen at the
     * previous call. The first call only records (the startup load already
     * counted against it). A failed read answers false and keeps the old
     * snapshot (§6.8: the next resume retries).
     */
    suspend fun changedSinceLastCheck(): Boolean {
        val current =
            try {
                decisions.decidedIds()
            } catch (cancellation: CancellationException) {
                throw cancellation // the resume went away, not a failed read
            } catch (_: Exception) {
                return false // keep the old snapshot; the next resume retries
            }
        val previous = lastSeen
        lastSeen = current
        return previous != null && previous != current
    }
}
