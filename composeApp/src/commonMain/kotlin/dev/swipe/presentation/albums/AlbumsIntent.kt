package dev.swipe.presentation.albums

import dev.swipe.presentation.home.HomeAlbumRow

/** What the Albums screen can ask for (FR-8, FR-37): open one album as a deck, or keep it out of Shuffle. */
sealed interface AlbumsIntent {
    /** Open the deck for one album row (FR-8); leaves as [AlbumsEvent.OpenDeck]. */
    data class OpenAlbum(
        val row: HomeAlbumRow,
    ) : AlbumsIntent

    /**
     * Flip one album's **In Shuffle** switch (FR-37, WP-8.15): switching off
     * adds the album's id to the exclusion store, switching on removes it —
     * the intent is named for what the app stores. The exclusion applies to
     * the next Shuffle deck; it never affects the album's own deck.
     */
    data class ToggleShuffleExclusion(
        val row: HomeAlbumRow,
    ) : AlbumsIntent

    /**
     * Re-run the album scan from the error state (WP-9.6, FR-8): the screen
     * goes back to [dev.swipe.presentation.ListStatus.Loading] and one fresh
     * scan answers.
     */
    data object Retry : AlbumsIntent
}
