package dev.swipe.presentation.queue

/**
 * The one state of [QueueViewModel] (FR-23–27, §6.7). The Review Queue screen's
 * shape: the live queue, the full-screen viewer's selection, the two-step delete
 * confirmation (in-app dialog, then the iOS dialog), and the last commit outcome.
 */
data class QueueUiState(
    /**
     * Queued ids, newest decision first (FR-23), straight from
     * [dev.swipe.data.DecisionStore.deleteQueue]; re-emitted live, and pruned of
     * ids PhotoKit no longer returns on every load (FR-28).
     */
    val items: List<String> = emptyList(),
    /**
     * The id shown in the full-screen viewer (FR-24); null when no tile is open.
     * Restoring the open item closes the viewer along with it.
     */
    val selected: String? = null,
    /**
     * The in-app confirm dialog ("Delete N items?", §5.8) is up. Set only by
     * [QueueIntent.RequestDelete]; the delete itself runs only on
     * [QueueIntent.ConfirmDelete] — the tap this dialog is the gate for (NFR-5).
     */
    val confirming: Boolean = false,
    /**
     * A commit is in flight: the iOS system dialog is up or the store write is
     * running. Further ConfirmDelete taps are ignored, so one confirmed batch can
     * never become two `MediaLibrary.delete` calls (FR-25).
     */
    val deleting: Boolean = false,
    /**
     * Terminal outcome of the last commit attempt; null before the first one and
     * again after a cancelled iOS dialog (FR-27 shows nothing). The UI renders
     * [QueueDeleteResult.Deleted] as the DeleteSuccess navigation (via
     * [QueueEvent.ShowDeleteSuccess]) and [QueueDeleteResult.Failed] as a toast.
     */
    val result: QueueDeleteResult? = null,
)

/** The last commit attempt's outcome, kept in [QueueUiState.result]. */
sealed interface QueueDeleteResult {
    /** PhotoKit deleted [count] assets and their rows are now DELETED (FR-26). */
    data class Deleted(
        val count: Int,
    ) : QueueDeleteResult

    /** PhotoKit failed with [message]; the queue is unchanged (§6.7). */
    data class Failed(
        val message: String,
    ) : QueueDeleteResult
}
