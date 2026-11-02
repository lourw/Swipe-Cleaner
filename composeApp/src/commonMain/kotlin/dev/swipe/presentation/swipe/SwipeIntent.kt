package dev.swipe.presentation.swipe

/**
 * Committed user actions on the Swipe screen (§6.6). The ViewModel only ever sees
 * committed intents — drag and animation state stay inside the `SwipeCard` composable.
 * WP-1.8 implements the three decision intents; `Undo`, `ToggleMute`,
 * `ConfirmFavoriteDelete`, and `DismissToast` join in WP-2.2 (FR-15, FR-16, FR-22).
 */
sealed interface SwipeIntent {
    /** Right swipe or the Keep button: records KEEP and advances (FR-12). */
    data object Keep : SwipeIntent

    /** Left swipe or the Delete button: records DELETE — queued, not deleted (FR-13). */
    data object Delete : SwipeIntent

    /** Up swipe or the Skip button: records SKIP for this session only (FR-14). */
    data object Skip : SwipeIntent
}
