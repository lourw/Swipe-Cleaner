package dev.swipe.presentation.queue

/**
 * Committed user actions on the Review Queue screen (FR-23–27, §6.7). Like
 * [dev.swipe.presentation.swipe.SwipeIntent], these are committed actions only —
 * scroll position and animation state stay inside the composables (WP-3.3).
 */
sealed interface QueueIntent {
    /**
     * Tap on a tile: opens [id] in the full-screen viewer (FR-24). Ignored while a
     * commit is in flight.
     */
    data class Open(
        val id: String,
    ) : QueueIntent

    /**
     * Back / Close: closes the full-screen viewer and any open confirm dialog;
     * touches nothing else. Screen dismissal itself is navigation (WP-3.3).
     */
    data object Close : QueueIntent

    /**
     * Restore in the viewer (FR-24): records KEEP for [id] via
     * [dev.swipe.domain.usecase.RestoreFromQueueUseCase], which drops it from the
     * queue; if it was the open item, the viewer closes too.
     */
    data class Restore(
        val id: String,
    ) : QueueIntent

    /**
     * "Delete N items": opens the in-app confirm dialog ([QueueUiState.confirming]).
     * **Nothing is deleted by this intent** (NFR-5); ignored on an empty queue or
     * while a commit is in flight.
     */
    data object RequestDelete : QueueIntent

    /**
     * The dialog's Delete button: the only intent that builds an
     * [dev.swipe.domain.usecase.InAppDeleteConfirmation] and runs
     * [dev.swipe.domain.usecase.CommitDeletionsUseCase] (NFR-5, §6.7). Ignored
     * unless the dialog is up — [RequestDelete] alone never reaches PhotoKit — and
     * ignored while a commit is in flight (FR-25: one call per batch).
     */
    data object ConfirmDelete : QueueIntent

    /**
     * The dialog's Cancel button: closes the confirm dialog. Like
     * [RequestDelete], it never deletes (NFR-5).
     */
    data object CancelConfirm : QueueIntent
}
