package dev.swipe.presentation.queue

import dev.swipe.domain.model.MediaType
import kotlin.time.Instant

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
     * Grid metadata for [items] (FR-23, NFR-8): the tile type, creation date and
     * duration the screen renders. Invariant: `tiles.map { it.id } == items` —
     * same order, same length. Fetched with every queue change in pages of 20
     * (§6.5 step 2, NFR-4) before the pair is published, so a state with `items`
     * set and `tiles` stale never exists; a failed fetch leaves tiles
     * metadata-less rather than dropping rows, and the next queue change retries.
     */
    val tiles: List<QueueTile> = emptyList(),
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
     * [QueueDeleteResult.Deleted] with a positive count as the DeleteSuccess
     * navigation (via [QueueEvent.ShowDeleteSuccess]) and
     * [QueueDeleteResult.Failed] as a toast. A `Deleted(0)` — the queue emptied
     * while the dialog was up — is reported with no event and no haptic: the
     * empty state is the feedback (§6.7).
     */
    val result: QueueDeleteResult? = null,
)

/**
 * One grid tile (FR-23, WP-3.3): the queued [id] with the metadata the tile
 * renders — the §5.7 video duration and the NFR-8 accessibility line. Fields
 * are null when the metadata fetch failed or the asset vanished mid-fetch;
 * the tile then falls back to a plain placeholder.
 */
data class QueueTile(
    /** Queued asset id; always one of [QueueUiState.items]. */
    val id: String,
    /** Still, video, or Live Photo; null when metadata is unavailable. */
    val type: MediaType?,
    /** Creation date for the tile's content description; null when unknown. */
    val createdAt: Instant?,
    /** Video playback length shown bottom-right (§5.7 GridTile); null unless video. */
    val durationMs: Long?,
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
