package dev.swipe.presentation.swipe

/**
 * Committed user actions on the Swipe screen (§6.6). The ViewModel only ever sees
 * committed intents — drag and animation state stay inside the `SwipeCard` composable.
 */
sealed interface SwipeIntent {
    /** Right swipe or the Keep button: records KEEP and advances (FR-12). */
    data object Keep : SwipeIntent

    /** Left swipe or the Delete button: records DELETE — queued, not deleted (FR-13). */
    data object Delete : SwipeIntent

    /** Up swipe or the Skip button: records SKIP for this session only (FR-14). */
    data object Skip : SwipeIntent

    /**
     * Undo button: pops the undo stack, removes the persisted decision, and moves the
     * card back (FR-15). A no-op when the stack is empty or the deck has finished.
     */
    data object Undo : SwipeIntent

    /** Tap on a playing video: flips [SwipeUiState.muted] for the session (FR-16). */
    data object ToggleMute : SwipeIntent

    /** "Delete anyway" in the favorite confirm toast: records DELETE and advances (FR-22). */
    data object ConfirmFavoriteDelete : SwipeIntent

    /** Toast timeout or manual dismissal: clears [SwipeUiState.toast] and any favorite confirm. */
    data object DismissToast : SwipeIntent

    /**
     * Share button: opens the iOS share sheet for the current card (FR-32). Records
     * no decision and never moves the deck.
     */
    data object Share : SwipeIntent
}
