package dev.swipe.presentation.swipe

import dev.swipe.domain.model.Decision

/**
 * Committed user actions on the Swipe screen (§6.6). The ViewModel only ever sees
 * committed intents — drag and animation state stay inside the `SwipeCard` composable.
 *
 * Every decision carries the id of the card the action was performed on (FR-13):
 * a tap or VoiceOver action that lands while a card is flying off names the card
 * that was on screen at that moment, and the ViewModel ignores it when it no
 * longer matches the card in front (FR-12).
 */
sealed interface SwipeIntent {
    /**
     * A verdict for the card with [id], ignored when [id] is not the card on screen.
     * Right swipe or the Keep button records KEEP and advances (FR-12).
     */
    data class Decide(
        val id: String,
        val verdict: Decision,
    ) : SwipeIntent

    /**
     * Undo button: pops the undo stack, removes the persisted decision, and moves the
     * card back (FR-15). A no-op when the stack is empty or the deck has finished.
     */
    data object Undo : SwipeIntent

    /** Tap on a playing video: flips [SwipeUiState.muted] for the session (FR-16). */
    data object ToggleMute : SwipeIntent

    /**
     * "Delete anyway" in the favorite confirm toast: confirms the delete queued for
     * [id] (FR-22). Ignored when [id] is not the pending favorite or the card on screen.
     */
    data class ConfirmFavoriteDelete(
        val id: String,
    ) : SwipeIntent

    /** Toast timeout or manual dismissal: clears [SwipeUiState.toast] and any favorite confirm. */
    data object DismissToast : SwipeIntent

    /**
     * Share button: opens the iOS share sheet for the current card (FR-32). Records
     * no decision and never moves the deck.
     */
    data object Share : SwipeIntent
}
