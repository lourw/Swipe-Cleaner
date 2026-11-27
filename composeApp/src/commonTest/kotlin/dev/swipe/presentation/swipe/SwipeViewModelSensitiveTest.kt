@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import dev.swipe.domain.model.Decision
import dev.swipe.fakes.FakeSensitivityAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-33 wiring in [SwipeViewModel]: the deck moves through [SensitiveFilter], so a
 * flagged id never becomes the current or next card and never gets a decision row
 * (hard rule 2 untouched: screening runs only after a successful write), the
 * setting-off path analyzes nothing, and analysis stays inside the
 * current + next 3 prefetch window (hard rule 6). Fixed seeds and virtual time
 * throughout (§8.2).
 */
class SwipeViewModelSensitiveTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** Collects every emitted state, including the intermediate ones. */
    private fun TestScope.collectStates(f: Fixture): List<SwipeUiState> {
        val states = mutableListOf<SwipeUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.viewModel.uiState.collect(states::add)
        }
        return states
    }

    @Test
    fun `FR-33 a flagged card never shows and records no decision`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val analyzer = FakeSensitivityAnalyzer(flagged = setOf("asset-1", "asset-3"))
            val f = Fixture(5, analyzer = analyzer)
            val states = collectStates(f)
            advanceUntilIdle()

            // asset-1 and asset-3 left the deck before the first publish.
            val shown = f.viewModel.uiState.value
            assertEquals("asset-2", shown.current?.id)

            f.viewModel.onIntent(SwipeIntent.Decide("asset-2", Decision.KEEP))
            advanceUntilIdle()

            // The advance skips asset-3 too.
            val advanced = f.viewModel.uiState.value
            assertEquals("asset-4", advanced.current?.id)

            // No emitted state ever showed a flagged id as current or next…
            val flagged = setOf("asset-1", "asset-3")
            for (s in states) {
                assertTrue(s.current?.id !in flagged, "flagged id showed as current: $s")
                assertTrue(s.next?.id !in flagged, "flagged id showed as next: $s")
            }
            // …and only the KEEP that happened was written.
            assertEquals(listOf("asset-2"), f.store.entries.map { it.id })
        }

    @Test
    fun `FR-33 with the setting off flagged cards show as normal`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val analyzer = FakeSensitivityAnalyzer(flagged = setOf("asset-1", "asset-3"))
            val f = Fixture(5, analyzer = analyzer, hideSensitive = false)
            advanceUntilIdle()

            // No analysis runs, nothing is skipped, nothing is recorded: a rebuilt
            // deck includes the flagged items again (AC2).
            val shown = f.viewModel.uiState.value
            assertEquals("asset-1", shown.current?.id)
            assertEquals("asset-2", shown.next?.id)
            assertEquals(emptyList(), analyzer.analyzed)
            assertEquals(emptyList(), f.store.entries)
        }

    @Test
    fun `FR-33 a hide advance skips a flagged next card`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val analyzer = FakeSensitivityAnalyzer(flagged = setOf("asset-2"))
            val f = Fixture(3, analyzer = analyzer)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()

            val afterHide = f.viewModel.uiState.value
            assertEquals("asset-3", afterHide.current?.id)
        }

    @Test
    fun `FR-33 analysis runs only inside the prefetch window`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val analyzer = FakeSensitivityAnalyzer()
            val f = Fixture(30, analyzer = analyzer)
            advanceUntilIdle()

            // Start + window refresh: only current + next 3 were ever asked.
            val windowAfterStart = (1..4).map { "asset-$it" }.toSet()
            assertTrue(analyzer.analyzed.isNotEmpty())
            assertTrue(analyzer.analyzed.all { it in windowAfterStart }, analyzer.analyzed.toString())

            f.viewModel.onIntent(SwipeIntent.Decide("asset-1", Decision.KEEP))
            advanceUntilIdle()

            // After one advance the window slid by exactly one card.
            val windowAfterAdvance = (1..5).map { "asset-$it" }.toSet()
            assertTrue(analyzer.analyzed.all { it in windowAfterAdvance }, analyzer.analyzed.toString())
        }
}
