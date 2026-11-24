package dev.swipe.presentation.swipe

import dev.swipe.fakes.FakeMediaLibrary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-9.2: [DeckCursor] keeps the deck, the head, and the §6.5 step 2 metadata
 * paging (including the FR-6 drop semantics extracted from `SwipeViewModel`,
 * where `SwipeViewModelBurstDeckTest` covers them end to end).
 */
class DeckCursorTest {
    private fun library(count: Int) = FakeMediaLibrary(photos(count))

    @Test
    fun `reset places the head on the first id with nothing loaded`() =
        runTest {
            val cursor = DeckCursor(library(25))
            cursor.reset((1..25).map { "asset-$it" })
            assertEquals(0, cursor.index)
            assertEquals("asset-1", cursor.currentId)
            assertTrue(cursor.metadata.isEmpty())
            assertFalse(cursor.isEmpty)
            assertFalse(cursor.isExhausted)
        }

    @Test
    fun `ensureMetadataLoaded pages the first page of 20 ids`() =
        runTest {
            val lib = library(25)
            val cursor = DeckCursor(lib)
            cursor.reset((1..25).map { "asset-$it" })
            cursor.ensureMetadataLoaded()
            assertEquals(listOf((1..20).map { "asset-$it" }), lib.itemsCalls)
            assertEquals(20, cursor.metadata.size)
        }

    @Test
    fun `ensureMetadataLoaded loads the next page within 5 of the loaded end`() =
        runTest {
            val lib = library(25)
            val cursor = DeckCursor(lib)
            cursor.reset((1..25).map { "asset-$it" })
            cursor.ensureMetadataLoaded()

            // Head at 14: 14 + 5 == 19 < 20 loaded, so no new page yet.
            repeat(14) { cursor.advance() }
            cursor.ensureMetadataLoaded()
            assertEquals(1, lib.itemsCalls.size)

            // Head at 15: within the trigger distance, so page 2 (the last 5) loads.
            cursor.advance()
            cursor.ensureMetadataLoaded()
            assertEquals(2, lib.itemsCalls.size)
            assertEquals((21..25).map { "asset-$it" }, lib.itemsCalls.last())
            assertEquals(25, cursor.metadata.size)
        }

    @Test
    fun `FR-6 ids a page does not resolve are dropped from the deck`() =
        runTest {
            val lib = library(25)
            lib.hiddenItemIds += setOf("asset-2", "asset-3")
            val cursor = DeckCursor(lib)
            cursor.reset((1..25).map { "asset-$it" })
            cursor.ensureMetadataLoaded()

            // The unresolved ids leave the deck; the rest keeps its order, and the
            // loaded region counts only what resolved (18 of the 20 requested).
            assertEquals(listOf("asset-1") + (4..25).map { "asset-$it" }, cursor.deck)
            assertEquals(18, cursor.metadata.size)
            assertEquals("asset-1", cursor.currentId)

            // The next trigger (head within 5 of the loaded end) asks for the ids
            // after the loaded region, which are the un-dropped tail.
            repeat(13) { cursor.advance() }
            cursor.ensureMetadataLoaded()
            assertEquals((21..25).map { "asset-$it" }, lib.itemsCalls.last())
            assertEquals(23, cursor.metadata.size)
        }

    @Test
    fun `FR-6 a failing page fetch keeps its ids and the next call retries`() =
        runTest {
            val lib = library(25)
            val cursor = DeckCursor(lib)
            cursor.reset((1..25).map { "asset-$it" })

            lib.failNextItems = IllegalStateException("fetch failed")
            kotlin.test.assertFailsWith<IllegalStateException> { cursor.ensureMetadataLoaded() }
            // Nothing dropped, nothing loaded past the failure point.
            assertEquals(25, cursor.deck.size)
            assertTrue(cursor.metadata.isEmpty())

            // The next call requests the same ids again and this time resolves them.
            cursor.ensureMetadataLoaded()
            assertEquals(2, lib.itemsCalls.size)
            assertEquals(lib.itemsCalls.first(), lib.itemsCalls.last())
            assertEquals(20, cursor.metadata.size)
        }

    @Test
    fun `advance and rewindTo move the head and exhaustion follows the end`() =
        runTest {
            val cursor = DeckCursor(library(2))
            cursor.reset(listOf("asset-1", "asset-2"))
            cursor.advance()
            assertEquals("asset-2", cursor.currentId)
            cursor.advance()
            assertNull(cursor.currentId)
            assertTrue(cursor.isExhausted)
            cursor.rewindTo(0)
            assertEquals("asset-1", cursor.currentId)
            assertFalse(cursor.isExhausted)
        }

    @Test
    fun `itemAt is null before metadata arrives and past the deck end`() =
        runTest {
            val cursor = DeckCursor(library(2))
            cursor.reset(listOf("asset-1", "asset-2"))
            assertNull(cursor.itemAt(0))
            cursor.ensureMetadataLoaded()
            assertEquals("asset-1", cursor.itemAt(0)?.id)
            assertNull(cursor.itemAt(2))
        }

    @Test
    fun `reset clears the previous deck metadata and head`() =
        runTest {
            val cursor = DeckCursor(library(3))
            cursor.reset(listOf("asset-1", "asset-2", "asset-3"))
            cursor.ensureMetadataLoaded()
            cursor.advance()

            cursor.reset(listOf("asset-2"))
            assertEquals(0, cursor.index)
            assertTrue(cursor.metadata.isEmpty())
            assertEquals("asset-2", cursor.currentId)
        }

    @Test
    fun `an empty deck is empty and exhausted at once`() =
        runTest {
            val cursor = DeckCursor(library(1))
            cursor.reset(emptyList())
            assertTrue(cursor.isEmpty)
            assertTrue(cursor.isExhausted)
            assertNull(cursor.currentId)
        }

    @Test
    fun `paging constants follow the spec`() {
        // §6.5 step 2: pages of 20, next page within 5 of the loaded end.
        assertEquals(20, DeckCursor.PAGE_SIZE)
        assertEquals(5, DeckCursor.TRIGGER)
    }
}
