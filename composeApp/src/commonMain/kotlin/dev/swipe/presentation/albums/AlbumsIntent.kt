package dev.swipe.presentation.albums

import dev.swipe.presentation.home.HomeAlbumRow

/** What the Albums screen can ask for (FR-8): open one album as a deck. */
sealed interface AlbumsIntent {
    /** Open the deck for one album row (FR-8); leaves as [AlbumsEvent.OpenDeck]. */
    data class OpenAlbum(
        val row: HomeAlbumRow,
    ) : AlbumsIntent
}
