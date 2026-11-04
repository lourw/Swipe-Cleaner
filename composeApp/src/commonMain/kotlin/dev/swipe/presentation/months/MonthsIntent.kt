package dev.swipe.presentation.months

import dev.swipe.domain.model.MonthBucket

/** What the By month screen can ask for (FR-7): open one month as a deck. */
sealed interface MonthsIntent {
    /** Open the deck for one month bucket; leaves as [MonthsEvent.OpenDeck]. */
    data class OpenMonth(
        val bucket: MonthBucket,
    ) : MonthsIntent
}
