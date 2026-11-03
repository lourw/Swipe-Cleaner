package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
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
 * The one batch delete (FR-25–27, §6.7) — the only caller of [MediaLibrary.delete] in
 * `commonMain` (NFR-5, AGENTS.md rule 1; guarded by `scripts/check-rules.sh`).
 *
 * Flow (§6.7):
 * 1. Re-read [DecisionStore.deleteQueue] and keep only ids present in **both** the
 *    [confirmation] and the current queue — an id restored or pruned while the
 *    dialog was up is gone from the queue and is never deleted (NFR-5). The token
 *    carries the whole queue as it stood when Delete was tapped (FR-25).
 * 2. Call [MediaLibrary.delete] exactly **once** with that list (FR-25: one PhotoKit
 *    change request for the whole batch, one iOS system dialog).
 * 3. Map the outcome:
 *    - [DeleteResult.Deleted] → [DecisionStore.markDeleted] for exactly those ids,
 *      [CommitOutcome.Success] with the count (FR-26).
 *    - [DeleteResult.Cancelled] → **no store call at all** → [CommitOutcome.Cancelled]
 *      (FR-27: the queue stays exactly as it was).
 *    - [DeleteResult.Failed] → **no store call at all** → [CommitOutcome.Error] (the
 *      queue stays as it was and the caller surfaces [CommitOutcome.Error.message]).
 *
 * An empty intersection (everything the user confirmed vanished while the dialog was
 * up) deletes nothing without calling the library — no iOS dialog for an empty batch,
 * mirroring PhotoKit's zero-asset short-circuit — and reports
 * `Success(deleted = 0)`.
 */
class CommitDeletionsUseCase(
    private val library: MediaLibrary,
    private val decisions: DecisionStore,
) {
    /** Runs the confirmed batch delete described above. */
    suspend operator fun invoke(confirmation: InAppDeleteConfirmation): CommitOutcome {
        val queued = decisions.deleteQueue().first().toSet()
        // Confirmation order is kept: it is the order the user read the list in.
        val ids = confirmation.ids.filter { it in queued }
        if (ids.isEmpty()) return CommitOutcome.Success(deleted = 0)
        return when (val result = library.delete(ids)) {
            DeleteResult.Deleted -> {
                decisions.markDeleted(ids)
                CommitOutcome.Success(deleted = ids.size)
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
    /** PhotoKit deleted the assets; [deleted] queue rows are now DELETED (FR-26). */
    data class Success(
        val deleted: Int,
    ) : CommitOutcome

    /** The user dismissed the iOS dialog; nothing was stored (FR-27). */
    data object Cancelled : CommitOutcome

    /** PhotoKit failed with [message]; nothing was stored (§6.7's error branch). */
    data class Error(
        val message: String,
    ) : CommitOutcome
}
