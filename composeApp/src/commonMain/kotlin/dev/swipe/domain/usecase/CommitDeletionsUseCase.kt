package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaDeleter
import dev.swipe.domain.model.DeleteResult
import kotlinx.coroutines.flow.first

/**
 * Proof that the user tapped **Delete** in the in-app confirm dialog (NFR-5, §6.7).
 *
 * Carries the exact ids the dialog showed — the newest-first list the user counted
 * ("Delete N items?") — so the batch that reaches PhotoKit is the batch that was
 * confirmed, never a list assembled after the fact.
 *
 * **The constructor is `internal` and the class has no factory, companion, or default
 * instance** (§6.7, NFR-5): code outside this module cannot construct one at all, and
 * the only construction site inside it is `QueueViewModel` when it handles
 * `QueueIntent.ConfirmDelete` — the tap on the dialog's Delete button. That is the
 * type-level half of the deletion-safety rule: [CommitDeletionsUseCase] accepts *only*
 * this token, so no code path can ask it to delete without the dialog having been
 * answered first. (The behavioral half is tested by
 * `NFR-5 deleting with no in-app confirmation does nothing`.)
 */
class InAppDeleteConfirmation
    internal constructor(
        /** The queued ids the user saw in the dialog, in the order the dialog listed them. */
        val ids: List<String>,
    )

/**
 * The one batch delete (FR-25–27, §6.7) — the only consumer of [MediaDeleter], and so
 * the only caller of [MediaDeleter.delete] in `commonMain` (NFR-5, AGENTS.md rule 1;
 * guarded by `scripts/check-rules.sh`, and by the type itself: this is the only class
 * the Koin graph hands a [MediaDeleter] to, and no other production file may even
 * name the type — WP-9.1).
 *
 * Flow (§6.7):
 * 1. Re-read [DecisionStore.deleteQueue] and keep only ids present in **both** the
 *    [confirmation] and the current queue — an id restored or pruned while the
 *    dialog was up is gone from the queue and is never deleted (NFR-5). The token
 *    carries the whole queue as it stood when Delete was tapped (FR-25).
 * 2. Call [MediaDeleter.delete] exactly **once** with that list (FR-25: one PhotoKit
 *    change request for the whole batch, one iOS system dialog).
 * 3. Map the outcome:
 *    - [DeleteResult.Deleted] → [DecisionStore.markDeleted] for exactly the ids the
 *      library confirmed gone, [CommitOutcome.Success] with the count and the count
 *      of ids that could not be resolved — those stay queued and the success screen
 *      says so ("N deleted, M couldn't be found", FR-26, WP-7.6).
 *    - [DeleteResult.Cancelled] → **no store call at all** → [CommitOutcome.Cancelled]
 *      (FR-27: the queue stays exactly as it was).
 *    - [DeleteResult.Failed] → **no store call at all** → [CommitOutcome.Error] (the
 *      queue stays as it was and the caller surfaces [CommitOutcome.Error.message]).
 *
 * An empty intersection (everything the user confirmed vanished while the dialog was
 * up) deletes nothing without calling the library — no iOS dialog for an empty batch,
 * mirroring PhotoKit's zero-asset short-circuit — and reports
 * `Success(deleted = 0, notFound = 0)`.
 */
class CommitDeletionsUseCase(
    private val deleter: MediaDeleter,
    private val decisions: DecisionStore,
) {
    /** Runs the confirmed batch delete described above. */
    suspend operator fun invoke(confirmation: InAppDeleteConfirmation): CommitOutcome {
        val queued = decisions.deleteQueue().first().toSet()
        // Confirmation order is kept: it is the order the user read the list in.
        val ids = confirmation.ids.filter { it in queued }
        if (ids.isEmpty()) return CommitOutcome.Success(deleted = 0)
        return when (val result = deleter.delete(ids)) {
            is DeleteResult.Deleted -> {
                // FR-26 (WP-7.6): PhotoKit resolves ids by `localIdentifier`, and an
                // id it cannot fetch — a burst frame, or an asset outside a Limited
                // selection — was **not** deleted and stays queued. Only the ids the
                // library reports gone (deleted now, or already gone: the §6.8 retry
                // convergence) flip to DELETED, so stats (FR-10) and decks (FR-9)
                // never count an asset that is still in the library.
                val deleted = ids.filter { it in result.ids }
                decisions.markDeleted(deleted)
                CommitOutcome.Success(deleted = deleted.size, notFound = ids.size - deleted.size)
            }

            DeleteResult.Cancelled -> {
                CommitOutcome.Cancelled
            }

            is DeleteResult.Failed -> {
                CommitOutcome.Error(result.message)
            }
        }
    }
}

/** Outcome of one [CommitDeletionsUseCase] run — the three §6.7 branches. */
sealed interface CommitOutcome {
    /**
     * PhotoKit finished the batch: [deleted] queue rows are now DELETED (FR-26) and
     * [notFound] ids could not be resolved — they stay queued, and the success screen
     * reports both counts ("N deleted, M couldn't be found", WP-7.6).
     */
    data class Success(
        val deleted: Int,
        val notFound: Int = 0,
    ) : CommitOutcome

    /** The user dismissed the iOS dialog; nothing was stored (FR-27). */
    data object Cancelled : CommitOutcome

    /** PhotoKit failed with [message]; nothing was stored (§6.7's error branch). */
    data class Error(
        val message: String,
    ) : CommitOutcome
}
