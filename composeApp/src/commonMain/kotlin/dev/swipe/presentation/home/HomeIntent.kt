package dev.swipe.presentation.home

import dev.swipe.domain.model.AlbumSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter

/**
 * Everything the Home screen can ask for (§6.5, FR-4, FR-5, FR-7, FR-10, FR-34):
 * change the media-type filter, open a deck for a source, open the To Delete
 * queue, open Settings. Navigation itself goes out as [HomeEvent].
 */
sealed interface HomeIntent {
    /**
     * Pick the media-type toggle (FR-5): the choice persists across launches
     * (WP-5.3 wires the store), reloads every count under the new filter, and
     * applies to whatever deck the user opens next (§5.8: "applies to the next
     * deck").
     */
    data class SetTypeFilter(
        val type: MediaTypeFilter,
    ) : HomeIntent

    /**
     * Open the deck for one source (FR-7): **Shuffle everything**, a **Smart
     * filters** row, or one **Albums** row (WP-5.3).
     */
    sealed interface OpenSource : HomeIntent {
        /** The FR-4 Shuffle-everything row. */
        data object Shuffle : OpenSource

        /** One Smart filters row (FR-6); opens as `DeckSource.Smart(kind)`. */
        data class Smart(
            val kind: SmartFilter,
        ) : OpenSource

        /** One Albums row (FR-8); opens as `DeckSource.Album(id, title)`. */
        data class Album(
            val source: AlbumSource,
        ) : OpenSource
    }

    /** Open the **To Delete** queue (FR-20). */
    data object OpenQueue : HomeIntent

    /** Open the **Settings** screen (FR-34). */
    data object OpenSettings : HomeIntent
}
