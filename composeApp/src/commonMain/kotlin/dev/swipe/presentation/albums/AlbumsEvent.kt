package dev.swipe.presentation.albums

import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter

/**
 * One-shot navigation requests from [AlbumsViewModel] to the App host (§6.2).
 * [OpenDeck] carries the screen's media-type filter (FR-5), so the deck the
 * row opens matches the counts on screen.
 */
sealed interface AlbumsEvent {
    /** Open the Deck for the tapped album with [typeFilter] applied (FR-5, FR-8). */
    data class OpenDeck(
        val source: DeckSource.Album,
        val typeFilter: MediaTypeFilter,
    ) : AlbumsEvent
}
