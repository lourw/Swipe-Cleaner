package dev.swipe.presentation.home

import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter

/**
 * One-shot navigation requests handed to the App host through
 * [HomeViewModel.events] (§6.2). [OpenDeck] carries the media-type filter that
 * was selected when the row was tapped (§5.8: "applies to the next deck"), and
 * [dev.swipe.presentation.deck.DeckViewModel] (WP-5.3) builds the deck with it.
 */
sealed interface HomeEvent {
    /** Open the Deck for [source] with [typeFilter] applied (FR-5). */
    data class OpenDeck(
        val source: DeckSource,
        val typeFilter: MediaTypeFilter,
    ) : HomeEvent

    /** Open the **To Delete** queue (FR-10). */
    data object OpenQueue : HomeEvent

    /** Open the **Settings** screen (FR-29). */
    data object OpenSettings : HomeEvent
}
