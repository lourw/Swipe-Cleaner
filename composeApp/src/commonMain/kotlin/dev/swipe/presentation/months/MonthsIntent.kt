package dev.swipe.presentation.months

import dev.swipe.domain.model.MonthBucket

/** What the By month screen can ask for (FR-7): open one month as a deck. */
sealed interface MonthsIntent {
    /** Open the deck for one month bucket; leaves as [MonthsEvent.OpenDeck]. */
    data class OpenMonth(
        val bucket: MonthBucket,
    ) : MonthsIntent

    /**
     * Re-run the month scan from the error state (WP-9.6, FR-7): the screen
     * goes back to [dev.swipe.presentation.ListStatus.Loading] and one fresh
     * scan answers.
     */
    data object Retry : MonthsIntent

    /**
     * The screen became the resumed destination again — back from a deck it
     * opened, or the app returning to the foreground. Re-runs the month
     * counts only when the decided set changed since the previous check, so
     * "Reset all decisions" (FR-30) and every other decision change land
     * without a reload on a no-change resume (§6.5).
     */
    data object Resumed : MonthsIntent
}
