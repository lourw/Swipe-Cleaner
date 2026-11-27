@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeSensitivityAnalyzer
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-33 (T2): [SensitiveFilter] screens only prefetch-window ids through
 * `SensitivityAnalyzer` and drops flagged ones from the deck — never a
 * whole-library scan (hard rule 6), never videos (FR-33), and nothing while
 * the setting is off or the analyzer is unavailable.
 */
class SensitiveFilterTest {
    private fun deck(count: Int): DeckCursor =
        DeckCursor(FakeMediaLibrary(photos(count))).also {
            it.reset((1..count).map { i -> "asset-$i" })
        }

    @Test
    fun `FR-33 settle drops a flagged current card and the next one moves up`() =
        runTest {
            val analyzer = FakeSensitivityAnalyzer(flagged = setOf("asset-1"))
            val cursor = deck(10)
            SensitiveFilter(analyzer, enabled = { true }).settle(cursor)
            assertEquals("asset-2", cursor.currentId)
            assertEquals(0, cursor.index)
            assertFalse("asset-1" in cursor.deck)
        }

    @Test
    fun `FR-33 settle keeps dropping until current and next are both clean`() =
        runTest {
            val analyzer = FakeSensitivityAnalyzer(flagged = setOf("asset-1", "asset-2", "asset-3"))
            val cursor = deck(10)
            SensitiveFilter(analyzer, enabled = { true }).settle(cursor)
            assertEquals("asset-4", cursor.currentId)
            assertEquals("asset-5", cursor.deck.getOrNull(1))
        }

    @Test
    fun `FR-33 nothing is analyzed while the setting is off`() =
        runTest {
            val analyzer = FakeSensitivityAnalyzer(flagged = setOf("asset-1"))
            val cursor = deck(10)
            SensitiveFilter(analyzer, enabled = { false }).settle(cursor)
            assertTrue(analyzer.analyzed.isEmpty())
            assertEquals((1..10).map { "asset-$it" }, cursor.deck)
        }

    @Test
    fun `FR-33 nothing is analyzed when analysis is unavailable`() =
        runTest {
            val analyzer = FakeSensitivityAnalyzer(flagged = setOf("asset-1"), available = false)
            val cursor = deck(10)
            val filter = SensitiveFilter(analyzer, enabled = { true })
            filter.settle(cursor)
            filter.screenAhead(this, cursor)
            advanceUntilIdle()
            assertTrue(analyzer.analyzed.isEmpty())
        }

    @Test
    fun `FR-33 analysis stays inside the prefetch window`() =
        runTest {
            val analyzer = FakeSensitivityAnalyzer()
            val cursor = deck(30)
            val filter = SensitiveFilter(analyzer, enabled = { true })
            filter.settle(cursor)
            filter.screenAhead(this, cursor)
            advanceUntilIdle()
            // Settle awaits current + next, screenAhead adds +2 and +3; the verdict
            // cache means each id is asked about exactly once (hard rule 6, AC4).
            assertEquals((1..4).map { "asset-$it" }, analyzer.analyzed)
        }

    @Test
    fun `FR-33 screenAhead drops a flagged id ahead without touching current or next`() =
        runTest {
            val analyzer = FakeSensitivityAnalyzer(flagged = setOf("asset-3"))
            val cursor = deck(10)
            SensitiveFilter(analyzer, enabled = { true }).screenAhead(this, cursor)
            advanceUntilIdle()
            assertEquals(listOf("asset-1", "asset-2", "asset-4"), cursor.deck.take(3))
            assertEquals("asset-1", cursor.currentId)
        }

    @Test
    fun `FR-33 videos are not analyzed`() =
        runTest {
            val analyzer = FakeSensitivityAnalyzer(flagged = setOf("asset-2"))
            val items = mixedDeck(4, setOf(2))
            val cursor =
                DeckCursor(FakeMediaLibrary(items)).also {
                    it.reset(items.map { item -> item.id })
                }
            val filter = SensitiveFilter(analyzer, enabled = { true })
            filter.settle(cursor)
            filter.screenAhead(this, cursor)
            advanceUntilIdle()
            assertFalse("asset-2" in analyzer.analyzed)
        }
}
