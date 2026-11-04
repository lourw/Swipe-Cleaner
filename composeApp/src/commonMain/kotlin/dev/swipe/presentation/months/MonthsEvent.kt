package dev.swipe.presentation.months

import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter

/**
 * One-shot navigation requests from [MonthsViewModel] to the App host (§6.2).
 * [OpenDeck] carries the screen's media-type filter (FR-5), so the month deck
 * opens with exactly the membership the bucket counted.
 */
sealed interface MonthsEvent {
    /** Open the Deck for the tapped month with [typeFilter] applied (FR-5, FR-7). */
    data class OpenDeck(
        val source: DeckSource.Month,
        val typeFilter: MediaTypeFilter,
    ) : MonthsEvent
}
