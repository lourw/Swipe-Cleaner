package dev.swipe.presentation.swipe

import dev.swipe.domain.model.Progress
import dev.swipe.domain.model.SessionSummary

/**
 * The one state of [SwipeViewModel] (REQUIREMENTS §6.6). Field-for-field the spec
 * shape, plus [revertToken] — the failed-save revert event the card watches (NFR-7),
 * documented in §6.6.
 */
data class SwipeUiState(
    /** The card under review; null once the deck is exhausted (§6.6). */
    val current: CardState? = null,
    /** The card rendered under [current] (FR-11); null at the deck's tail. */
    val next: CardState? = null,
    /** `reviewed / total` for the session (FR-19). */
    val progress: Progress = Progress(reviewed = 0, total = 0),
    /** Size of the pending-delete queue, from [dev.swipe.data.DecisionStore.deleteQueue] (FR-10, FR-23). */
    val queueCount: Int = 0,
    /** Undo affordance; the undo stack arrives with WP-2.2 (FR-15). */
    val canUndo: Boolean = false,
    /** Video mute state, remembered for the session (FR-16); videos start muted. */
    val muted: Boolean = true,
    /** Transient message above the cards ([ToastMessage]); null when none. */
    val toast: ToastMessage? = null,
    /** Session summary once the deck runs out (FR-21); built by WP-2.2. */
    val finished: SessionSummary? = null,
    /**
     * Increments every time a failed decision write sends the card back (NFR-7).
     * One-shot events ride on state fields the composable watches for changes
     * (§6.6); the card plays its return animation on each change.
     */
    val revertToken: Long = 0L,
)

/**
 * One-shot message shown by the §5.7 Toast in [SwipeUiState.toast]. WP-2.2 adds the
 * "Undone" message (FR-15) and the favorite confirm (FR-22).
 */
sealed interface ToastMessage {
    /** NFR-7: the decision write failed, so the card was reverted and stays put. */
    data object SaveFailed : ToastMessage
}
