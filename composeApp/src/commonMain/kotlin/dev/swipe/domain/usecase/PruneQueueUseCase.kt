package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Drops queued ids PhotoKit no longer returns (FR-28, §6.8): an asset deleted in the
 * Photos app while Swipe Cleaner was away must not sit in the queue waiting for a
 * delete that can only fail. Runs on every queue load, and WP-3.2 re-runs it on each
 * `MediaLibrary.changes()` emission.
 *
 * The check is an existence probe, not a metadata load: the queue is asked in pages
 * of [EXISTENCE_PAGE_SIZE] ids (§6.5 step 2's paging, NFR-4 / AGENTS.md rule 6) and
 * ids missing from [MediaLibrary.items]' answers are dropped from the store with one
 * [DecisionStore.dropFromQueue] call.
 *
 * **Silently** (FR-28): no result surfaces to the user, and a failed drop is
 * swallowed — the rows just stay queued for the next load. Cancellation still
 * propagates. The return value exists for tests, not for the UI.
 *
 * WP-7.6: the drop goes through [DecisionStore.dropFromQueue], whose SQL deletes only
 * **pending-DELETE** rows, instead of the undo-shaped [DecisionStore.remove] with a
 * synthetic session id — so a prune can never erase a KEEP row, whatever an id's row
 * holds by the time the write lands.
 */
class PruneQueueUseCase(
    private val library: MediaLibrary,
    private val decisions: DecisionStore,
) {
    /** Prunes the current queue; returns the ids dropped from the store. */
    suspend operator fun invoke(): List<String> {
        val queue = decisions.deleteQueue().first()
        if (queue.isEmpty()) return emptyList()
        val present =
            queue
                .chunked(EXISTENCE_PAGE_SIZE)
                .flatMap { page -> library.items(page).map { it.id } }
                .toSet()
        val missing = queue.filterNot { it in present }
        if (missing.isNotEmpty()) {
            try {
                decisions.dropFromQueue(missing)
            } catch (cancellation: CancellationException) {
                throw cancellation // teardown, not a failed prune
            } catch (_: Exception) {
                // FR-28 keeps pruning silent: rows this load could not drop are
                // retried by the next one.
            }
        }
        return missing
    }

    companion object {
        /** Existence probe page size; §6.5 step 2 caps `items` at 20 ids per call. */
        const val EXISTENCE_PAGE_SIZE = 20
    }
}
