package dev.swipe.presentation.queue

/**
 * One-shot ViewModel → UI events (§6.6 events policy, applied to the Queue screen
 * per WP-3.1): they ride a `Channel` exposed as [QueueViewModel.events], so each
 * is delivered to exactly one collector and never replays on recomposition.
 * Navigation lives here, never in the ViewModel (AGENTS.md convention).
 */
sealed interface QueueEvent {
    /**
     * The batch was deleted (FR-26): navigate to the DeleteSuccess screen, which
     * shows [count] deleted and, when nonzero, [notFound] ids that could not be
     * resolved and stay queued ("N deleted, M couldn't be found", WP-7.6). Fires
     * together with the `notificationSuccess` haptic (§5.6, §6.4) and only when at
     * least one of the two counts is at least 1 — a zero-count commit (the queue
     * emptied while the dialog was up) reports success through
     * [QueueUiState.result] alone, with the empty state as the feedback.
     */
    data class ShowDeleteSuccess(
        val count: Int,
        val notFound: Int = 0,
    ) : QueueEvent

    /**
     * The commit failed with PhotoKit's [message] (§6.7 error branch): show a
     * toast; the queue is unchanged. A cancelled iOS dialog emits **nothing**
     * (FR-27).
     */
    data class ShowDeleteError(
        val message: String,
    ) : QueueEvent

    /**
     * A restore's KEEP write failed (NFR-7's convention applied to FR-24): show a
     * toast; the item stays queued.
     */
    data object RestoreFailed : QueueEvent
}
