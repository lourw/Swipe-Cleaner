@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.fakes.FakeMediaSharer
import dev.swipe.fakes.FakeSessionSkips
import dev.swipe.fakes.FakeSessionStore
import dev.swipe.fakes.FakeVideoPreloader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Undo (FR-15) for [SwipeViewModel]: removal before the index moves back, the 50-deep
 * stack, the single-flight guard against rapid taps, the decide-in-flight guard
 * (WP-7.2), skip undo, and the empty-stack no-op. Fixed seeds and virtual time
 * throughout (§8.2).
 */
class SwipeViewModelUndoTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-15 undo restores last card`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(SwipeIntent.Keep)
                val after = awaitItem()
                assertEquals("asset-2", after.current?.id)
                assertTrue(after.canUndo)

                f.viewModel.onIntent(SwipeIntent.Undo)
                val undone = awaitItem()
                assertEquals("asset-1", undone.current?.id)
                assertEquals("asset-2", undone.next?.id)
                assertEquals(0, undone.progress.reviewed)
                assertEquals(ToastMessage.Undone, undone.toast)
                // The stack is empty again, so the button goes away (FR-15, §6.6).
                assertFalse(undone.canUndo)
            }
            // The stored verdict is gone, so the card really comes back (FR-15)…
            assertEquals(listOf("asset-1"), f.store.removeCalls)
            assertEquals(emptyList(), f.store.entries)
            // …and the position moved back with it (FR-20).
            assertEquals(listOf("session-1" to 1, "session-1" to 0), f.sessionStore.updatePositionCalls)
        }

    @Test
    fun `FR-15 undo with an empty stack is a no-op`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)

            f.viewModel.uiState.test {
                val ready = awaitDeckReady()
                assertFalse(ready.canUndo)

                f.viewModel.onIntent(SwipeIntent.Undo)
                expectNoEvents()
            }
            assertEquals(emptyList(), f.store.removeCalls)
        }

    @Test
    fun `FR-15 undo stack holds at most 50 steps`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(60)
            advanceUntilIdle()

            repeat(55) {
                f.viewModel.onIntent(SwipeIntent.Keep)
                advanceUntilIdle()
            }
            repeat(55) {
                f.viewModel.onIntent(SwipeIntent.Undo)
                advanceUntilIdle()
            }

            // The first five keeps fell off the 50-cap, so undo reaches asset-6 and stops.
            val s = f.viewModel.uiState.value
            assertEquals("asset-6", s.current?.id)
            assertEquals(5, s.progress.reviewed)
            assertEquals(50, f.store.removeCalls.size)
            assertEquals(
                setOf("asset-1", "asset-2", "asset-3", "asset-4", "asset-5"),
                f.store.entries
                    .map { it.id }
                    .toSet(),
            )
            assertFalse(s.canUndo)
        }

    @Test
    fun `FR-15 undo removes the persisted decision before moving back`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val clock = FakeClock()
            val skips = FakeSessionSkips()
            val backing = FakeDecisionStore(clock, skips)
            lateinit var viewModel: SwipeViewModel
            // Snapshot the UI's reviewed count at the exact moment remove() runs.
            val store = OrderRecordingDecisionStore(backing) { viewModel.uiState.value.progress.reviewed }
            val library = FakeMediaLibrary(photos(3))
            viewModel =
                SwipeViewModel(
                    source = SOURCE,
                    typeFilter = MediaTypeFilter.ALL,
                    buildDeck = BuildDeckUseCase(library, store),
                    recordDecision = RecordDecisionUseCase(store),
                    undoDecision = UndoDecisionUseCase(store),
                    mediaLibrary = library,
                    mediaLoader = FakeMediaLoader(),
                    videoPreloader = FakeVideoPreloader(),
                    mediaSharer = FakeMediaSharer(),
                    decisions = store,
                    sessionStore = FakeSessionStore(clock, skips),
                    seedProvider = { SEED },
                )

            viewModel.uiState.test {
                awaitDeckReady()
                viewModel.onIntent(SwipeIntent.Keep)
                assertEquals("asset-2", awaitItem().current?.id)

                // A failed removal leaves everything in place: the decision stays
                // stored, the deck does not move, and the step goes back on the
                // stack for a retry (NFR-7 mirror).
                backing.failNextRemove = IllegalStateException("disk full")
                viewModel.onIntent(SwipeIntent.Undo)
                val failed = awaitItem()
                assertEquals("asset-2", failed.current?.id)
                assertEquals(1, failed.progress.reviewed)
                assertEquals(ToastMessage.SaveFailed, failed.toast)
                assertTrue(failed.canUndo)

                viewModel.onIntent(SwipeIntent.Undo)
                val undone = awaitItem()
                assertEquals("asset-1", undone.current?.id)
            }
            // When remove() ran the state still showed the card *after* the decision;
            // the index only moved back once the removal had returned (FR-15).
            assertEquals(listOf(1, 1), store.reviewedAtRemove)
            // Two attempts: the failed one and the successful retry.
            assertEquals(listOf("asset-1", "asset-1"), backing.removeCalls)
        }

    @Test
    fun `FR-15 rapid double undo removes exactly one decision`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            assertEquals(
                "asset-3",
                f.viewModel.uiState.value.current
                    ?.id,
            )

            // Two Undo taps dispatched back-to-back: the second is ignored while the
            // first removal is still in flight (single-flight), so exactly one stored
            // verdict is removed and stack and state stay coherent (FR-15).
            f.viewModel.onIntent(SwipeIntent.Undo)
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()

            assertEquals(listOf("asset-2"), f.store.removeCalls)
            val s = f.viewModel.uiState.value
            assertEquals("asset-2", s.current?.id)
            assertEquals(1, s.progress.reviewed)
            assertTrue(s.canUndo)

            // The surviving step still undoes cleanly.
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            assertEquals(
                "asset-1",
                f.viewModel.uiState.value.current
                    ?.id,
            )
            assertEquals(0, f.viewModel.uiState.value.progress.reviewed)
            assertEquals(listOf("asset-2", "asset-1"), f.store.removeCalls)
        }

    @Test
    fun `FR-15 undoing a skip brings the card back and clears this session's skip`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Skip)
            advanceUntilIdle()
            assertEquals(
                "asset-2",
                f.viewModel.uiState.value.current
                    ?.id,
            )
            assertEquals(setOf("asset-1"), f.sessionStore.skippedIds("session-1"))

            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()

            val s = f.viewModel.uiState.value
            assertEquals("asset-1", s.current?.id)
            assertEquals(0, s.progress.reviewed)
            assertEquals(ToastMessage.Undone, s.toast)
            // The skip row is gone, so the card is undecided again (FR-15, FR-14)…
            assertEquals(emptySet(), f.sessionStore.skippedIds("session-1"))
            assertEquals(listOf("asset-1"), f.store.removeCalls)
            // …and the deck still holds it, so it can be decided for real.
            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            assertEquals(
                "asset-2",
                f.viewModel.uiState.value.current
                    ?.id,
            )
            assertEquals(1, f.viewModel.uiState.value.progress.reviewed)
        }

    @Test
    fun `FR-15 undo is ignored while a decision write is in flight`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            val gate = CompletableDeferred<Unit>()
            f.store.gateNextRecord = gate

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(SwipeIntent.Keep)
                advanceUntilIdle()
                // The write is suspended on the gate: nothing has moved (NFR-7).
                expectNoEvents()

                // Undo during the write is ignored (WP-7.2): rewinding now would
                // re-show asset-1 while its KEEP is still completing.
                f.viewModel.onIntent(SwipeIntent.Undo)
                advanceUntilIdle()
                expectNoEvents()
                assertTrue(f.store.removeCalls.isEmpty())

                // Once the write lands the card advances normally…
                gate.complete(Unit)
                val after = awaitItem()
                assertEquals("asset-2", after.current?.id)
                assertTrue(after.canUndo)

                // …and Undo then works exactly as usual (FR-15).
                f.viewModel.onIntent(SwipeIntent.Undo)
                val undone = awaitItem()
                assertEquals("asset-1", undone.current?.id)
                assertEquals(ToastMessage.Undone, undone.toast)
            }
            // Exactly one removal, of the decided card, after the guard lifted.
            assertEquals(listOf("asset-1"), f.store.removeCalls)
        }

    @Test
    fun `FR-15 an undo dropped mid-write is not queued behind it`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            // Decide asset-1 for real, so the undo stack is non-empty and it is
            // the in-flight write, not an empty stack, that drops the next tap.
            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            assertEquals(
                "asset-2",
                f.viewModel.uiState.value.current
                    ?.id,
            )

            val gate = CompletableDeferred<Unit>()
            f.store.gateNextRecord = gate
            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()

            // Ignored while the asset-2 write is in flight: no removal, no rewind.
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            assertTrue(f.store.removeCalls.isEmpty())
            val held = f.viewModel.uiState.value
            assertEquals("asset-2", held.current?.id)
            assertEquals(1, held.progress.reviewed)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "asset-3",
                f.viewModel.uiState.value.current
                    ?.id,
            )

            // The dropped tap did not queue up: the next Undo rewinds exactly
            // one step, back to asset-2 — not past it.
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            assertEquals(
                "asset-2",
                f.viewModel.uiState.value.current
                    ?.id,
            )
            assertEquals(listOf("asset-2"), f.store.removeCalls)
        }
}
