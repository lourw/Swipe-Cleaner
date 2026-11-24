package dev.swipe.presentation.swipe

import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.MediaItem

/**
 * The deck under review: the id list, the head position, and the metadata paged
 * in around the head (§6.5 step 2). Extracted from `SwipeViewModel` by WP-9.2;
 * the ViewModel keeps the decisions about *when* to page, this class keeps the
 * mechanics of *what* is loaded where.
 *
 * Paging: metadata in pages of [PAGE_SIZE], loading the next page while the
 * index is within [TRIGGER] of the loaded end, so the first card emits as soon
 * as its page is in.
 *
 * FR-6 (WP-8.3): an id whose metadata does not come back is **dropped from the
 * deck**, not left as a card that can never show. The deck is built from ids a
 * source fetch enumerated; between then and paging an asset can go away (deleted
 * in the Photos app) or stay unresolvable, and the deck must run on to the
 * reviewable cards instead of ending on the gap. Drops only ever remove
 * positions at or beyond the loaded end, and the loaded region always contains
 * the index, so undo entries — whose `index` is behind the head — are never
 * disturbed. A failing page fetch keeps its ids in the deck (nothing is
 * dropped on a failed fetch) and the next call retries.
 */
class DeckCursor(
    private val mediaLibrary: MediaLibrary,
) {
    /** The deck as currently known: built ids minus the ones paging dropped (FR-6). */
    var deck: List<String> = emptyList()
        private set

    /** The head position: the card being reviewed. */
    var index: Int = 0
        private set

    /** Resolved metadata by id; ids ahead of the loaded end are simply absent. */
    val metadata: Map<String, MediaItem>
        get() = loaded

    /** Ids at or beyond the loaded end; the paging loop only ever starts here. */
    private var loadedCount = 0
    private val loaded = mutableMapOf<String, MediaItem>()

    /** The id on screen, or null when the deck is empty or the head is past its end. */
    val currentId: String? get() = deck.getOrNull(index)

    val isEmpty: Boolean get() = deck.isEmpty()

    /** Whether the head has run past the deck's end (the deck ran out, FR-21). */
    val isExhausted: Boolean get() = index >= deck.size

    /** Start over on a freshly built deck; the head and the loaded region reset. */
    fun reset(deck: List<String>) {
        this.deck = deck
        index = 0
        loadedCount = 0
        loaded.clear()
    }

    /** The head disposed its card and moved on. */
    fun advance() {
        index += 1
    }

    /** Undo returns the head to a position recorded earlier. */
    fun rewindTo(i: Int) {
        index = i
    }

    /** The item at [i], or null past the deck's end or before its metadata arrives. */
    fun itemAt(i: Int): MediaItem? = deck.getOrNull(i)?.let(loaded::get)

    /**
     * §6.5 step 2: page metadata in until the head has room, dropping ids the
     * page did not resolve (FR-6). A failing fetch propagates — its ids stay in
     * the deck untouched and the next call retries.
     */
    suspend fun ensureMetadataLoaded() {
        while (loadedCount < deck.size && index + TRIGGER >= loadedCount) {
            val page = deck.subList(loadedCount, minOf(deck.size, loadedCount + PAGE_SIZE))
            for (item in mediaLibrary.items(page)) {
                loaded[item.id] = item
            }
            // FR-6: ids the page did not resolve leave the deck; the loaded region
            // advances only over what it actually resolved (or dropped).
            val unresolved = page.filterNot { it in loaded }.toSet()
            if (unresolved.isNotEmpty()) {
                deck = deck.filterNot { it in unresolved }
            }
            loadedCount += page.size - unresolved.size
        }
    }

    companion object {
        /** §6.5 step 2: metadata pages of 20 ids. */
        const val PAGE_SIZE = 20

        /** §6.5 step 2: load the next page within 5 of the loaded end. */
        const val TRIGGER = 5
    }
}
