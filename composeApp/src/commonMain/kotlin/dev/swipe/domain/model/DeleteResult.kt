package dev.swipe.domain.model

/** Outcome of a batch delete through the iOS system dialog (FR-25–FR-27). */
sealed interface DeleteResult {
    /** PhotoKit removed the assets; the queue rows become DELETED (FR-26). */
    data object Deleted : DeleteResult

    /** The user tapped Don't Allow in the iOS dialog; the queue stays as it was (FR-27). */
    data object Cancelled : DeleteResult

    /** PhotoKit failed; the queue stays as it was and a toast shows [message] (FR-27, §6.7). */
    data class Failed(
        val message: String,
    ) : DeleteResult
}
