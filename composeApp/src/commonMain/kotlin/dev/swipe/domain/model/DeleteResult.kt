package dev.swipe.domain.model

/** Outcome of a batch delete through the iOS system dialog (FR-25–FR-27). */
sealed interface DeleteResult {
    /**
     * The batch finished; [ids] are no longer in the library — deleted by this call,
     * or already gone when it ran (§6.8's zero-fetch short-circuit, which is how a
     * retried commit converges after a failed `markDeleted`). Requested ids that are
     * **not** in [ids] could not be resolved — a burst frame, or an asset outside a
     * Limited selection — stay queued, and the success screen says so
     * ("N deleted, M couldn't be found", FR-26, WP-7.6).
     */
    data class Deleted(
        val ids: Set<String>,
    ) : DeleteResult

    /** The user tapped Don't Allow in the iOS dialog; the queue stays as it was (FR-27). */
    data object Cancelled : DeleteResult

    /** PhotoKit failed; the queue stays as it was and a toast shows [message] (FR-27, §6.7). */
    data class Failed(
        val message: String,
    ) : DeleteResult
}
