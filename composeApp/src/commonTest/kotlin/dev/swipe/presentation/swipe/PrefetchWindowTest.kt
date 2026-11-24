package dev.swipe.presentation.swipe

import dev.swipe.domain.model.MediaItem
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.fakes.FakeVideoPreloader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The §6.5 steps 3–4 [PrefetchWindow] in isolation: window slicing (current +
 * next 3, short at the deck tail), the cancel-then-prefetch call order with only
 * the ids that moved, and the video pre-playlist following the same window. The
 * ViewModel drives these refreshes after every move; its end-to-end sequences
 * live in `SwipeViewModelTest`.
 */
class PrefetchWindowTest {
    private val loader = FakeMediaLoader()
    private val videoPreloader = FakeVideoPreloader()
    private val window = PrefetchWindow(loader, videoPreloader)

    private val deck = (1..10).map { "asset-$it" }

    private val photoMetadata: Map<String, MediaItem> = photos(10).associateBy { it.id }

    @Test
    fun `6-5 the first refresh requests the window with nothing to cancel`() {
        window.refresh(deck, index = 0, metadata = photoMetadata)
        assertEquals(listOf(listOf("asset-1", "asset-2", "asset-3", "asset-4")), loader.prefetchCalls)
        assertEquals(emptyList<List<String>>(), loader.cancelPrefetchCalls)
    }

    @Test
    fun `6-5 a move cancels the ids that left and requests the new one with cancels first`() {
        window.refresh(deck, index = 0, metadata = photoMetadata)
        window.refresh(deck, index = 1, metadata = photoMetadata)
        assertEquals(
            listOf(
                listOf("asset-1", "asset-2", "asset-3", "asset-4"),
                listOf("asset-2", "asset-3", "asset-4", "asset-5"),
            ),
            loader.prefetchCalls,
        )
        assertEquals(listOf(listOf("asset-1")), loader.cancelPrefetchCalls)
    }

    @Test
    fun `6-5 a jump out of the window cancels every stale id at once`() {
        window.refresh(deck, index = 0, metadata = photoMetadata)
        window.refresh(deck, index = 6, metadata = photoMetadata)
        assertEquals(listOf(listOf("asset-1", "asset-2", "asset-3", "asset-4")), loader.cancelPrefetchCalls)
        assertEquals(
            listOf(
                listOf("asset-1", "asset-2", "asset-3", "asset-4"),
                listOf("asset-7", "asset-8", "asset-9", "asset-10"),
            ),
            loader.prefetchCalls,
        )
    }

    @Test
    fun `6-5 the window is short at the deck tail and empty past it`() {
        window.refresh(deck, index = 8, metadata = photoMetadata)
        assertEquals(listOf(listOf("asset-9", "asset-10")), loader.prefetchCalls)

        // Past the end: the stale window is cancelled, nothing is requested.
        window.refresh(deck, index = 10, metadata = photoMetadata)
        assertEquals(listOf(listOf("asset-9", "asset-10")), loader.cancelPrefetchCalls)
        assertEquals(1, loader.prefetchCalls.size) // the empty window added nothing
    }

    @Test
    fun `FR-16 window videos are pre-playlisted and released when they leave`() {
        val items = mixedDeck(6, videoPositions = setOf(2, 4))
        val metadata = items.associateBy { it.id }

        window.refresh(deck.take(6), index = 0, metadata = metadata)
        assertEquals(listOf("asset-2", "asset-4"), videoPreloader.preloadCalls)
        assertEquals(emptyList<String>(), videoPreloader.cancelCalls)

        // Slide to 2..5: asset-2 leaves, asset-5 (photo) adds nothing, asset-4 stays warm.
        window.refresh(deck.take(6), index = 2, metadata = metadata)
        assertEquals(listOf("asset-2"), videoPreloader.cancelCalls)
        assertEquals(listOf("asset-2", "asset-4"), videoPreloader.preloadCalls)

        // Slide to 5..6: asset-4 leaves too.
        window.refresh(deck.take(6), index = 4, metadata = metadata)
        assertEquals(listOf("asset-2", "asset-4"), videoPreloader.cancelCalls)
    }

    @Test
    fun `6-5 release cancels the warm windows once and later refreshes start clean`() {
        val items = mixedDeck(6, videoPositions = setOf(3))
        val metadata = items.associateBy { it.id }
        window.refresh(deck.take(6), index = 0, metadata = metadata)

        window.release()
        assertEquals(listOf(listOf("asset-1", "asset-2", "asset-3", "asset-4")), loader.cancelPrefetchCalls)
        assertEquals(listOf("asset-3"), videoPreloader.cancelCalls)

        // A second release with nothing warm is a no-op (the VM only clears once,
        // but the guard is the old onCleared behavior, kept).
        window.release()
        assertEquals(1, loader.cancelPrefetchCalls.size)
        assertEquals(1, videoPreloader.cancelCalls.size)
    }

    @Test
    fun `6-5 a deck shorter than the window prefetches only the deck`() {
        window.refresh(deck.take(2), index = 0, metadata = photoMetadata)
        assertEquals(listOf(listOf("asset-1", "asset-2")), loader.prefetchCalls)
    }
}
