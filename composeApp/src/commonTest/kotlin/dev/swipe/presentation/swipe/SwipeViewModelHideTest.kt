@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.HideResult
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeSessionSkips
import dev.swipe.fakes.buildDeckUseCase
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hide (FR-38) for [SwipeViewModel]: the write-before-advance rule (NFR-7) applied
 * to `MediaHider.setHidden`, the verdict-less undo step, the single-flight and
 * stale-id guards shared with [SwipeViewModel.decide], the FR-22 confirm interplay,
 * and the FR-21 exclusion — hides write no decision row, so the summary never
 * counts them. Fixed seeds and virtual time throughout (§8.2).
 */
class SwipeViewModelHideTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-38 hide advances the deck with no decision row and toasts Hidden`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()

            // The deck moved like a decision did…
            val s = f.viewModel.uiState.value
            assertEquals("asset-2", s.current?.id)
            assertEquals(1, s.progress.reviewed)
            assertEquals(ToastMessage.Hidden, s.toast)
            assertTrue(s.canUndo)
            assertFalse(s.hiding)
            // …but nothing was written: no decision row, only the hide itself.
            assertEquals(listOf("asset-1" to true), f.library.setHiddenCalls)
            assertEquals(emptyList(), f.store.entries)
            assertTrue(f.library.hiddenAssetIds.contains("asset-1"))
        }

    @Test
    fun `FR-38 a failed hide keeps the card and toasts HideFailed`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            f.library.failNextSetHidden = IllegalStateException("PhotoKit refused the change")

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()

            // NFR-7 mirror: the card stays exactly where it is, nothing advanced.
            val s = f.viewModel.uiState.value
            assertEquals("asset-1", s.current?.id)
            assertEquals(0, s.progress.reviewed)
            assertEquals(ToastMessage.HideFailed, s.toast)
            assertFalse(s.canUndo)
            assertFalse(s.hiding)
            assertEquals(emptyList(), f.store.entries)
        }

    @Test
    fun `FR-38 a FAILED result from the hide door keeps the card too`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            f.library.nextSetHiddenResult = HideResult.FAILED

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()

            val s = f.viewModel.uiState.value
            assertEquals("asset-1", s.current?.id)
            assertEquals(ToastMessage.HideFailed, s.toast)
            assertFalse(s.canUndo)
            // The fake only flips the flag on APPLIED, so the asset stays visible.
            assertTrue(f.library.hiddenAssetIds.isEmpty())
        }

    @Test
    fun `FR-38 hide is single-flight while the write is in flight`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            f.library.setHiddenGate = gate

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()

            // The write is in flight: hiding is up (the button disables), the card
            // has not moved, and a second tap is dropped, not queued.
            val inflight = f.viewModel.uiState.value
            assertTrue(inflight.hiding)
            assertEquals("asset-1", inflight.current?.id)
            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()
            assertEquals(listOf("asset-1" to true), f.library.setHiddenCalls)

            gate.complete(Unit)
            advanceUntilIdle()

            // One advance, one undo step — the dropped tap never doubled anything.
            val s = f.viewModel.uiState.value
            assertEquals("asset-2", s.current?.id)
            assertFalse(s.hiding)
            assertEquals(listOf("asset-1" to true), f.library.setHiddenCalls)
        }

    @Test
    fun `FR-38 hide ignores a stale id`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            // asset-2 is the card *behind* the top card (FR-12/FR-13 rule).
            f.viewModel.onIntent(SwipeIntent.Hide("asset-2"))
            advanceUntilIdle()

            assertEquals(emptyList(), f.library.setHiddenCalls)
            assertEquals(
                "asset-1",
                f.viewModel.uiState.value.current
                    ?.id,
            )
        }

    @Test
    fun `FR-38 hide is ignored while a decision write is in flight`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            f.store.gateNextRecord = gate

            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()
            // The deciding flag mirrors the write for the Hide button (§6.6).
            assertTrue(f.viewModel.uiState.value.deciding)

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()
            assertEquals(emptyList(), f.library.setHiddenCalls)

            gate.complete(Unit)
            advanceUntilIdle()

            // The decision landed on its own; the dropped hide never fired.
            assertEquals(
                "asset-2",
                f.viewModel.uiState.value.current
                    ?.id,
            )
            assertEquals(emptyList(), f.library.setHiddenCalls)
            assertFalse(f.viewModel.uiState.value.deciding)
        }

    @Test
    fun `FR-38 a decision during the hide write records once and the hide never double-advances`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            f.library.setHiddenGate = gate

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()
            // A verdict is *not* held by the hide write: it records and advances.
            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()
            assertEquals(
                "asset-2",
                f.viewModel.uiState.value.current
                    ?.id,
            )

            gate.complete(Unit)
            advanceUntilIdle()

            // The hide is durable in the library, but its stale-advance guard kept
            // the deck from moving twice and from pushing a hide step after the keep.
            assertEquals(
                "asset-2",
                f.viewModel.uiState.value.current
                    ?.id,
            )
            assertTrue(f.library.hiddenAssetIds.contains("asset-1"))
            assertEquals(listOf("asset-1" to Decision.KEEP), f.store.entries.map { it.id to it.decision })

            // Undo pops the keep, not a phantom hide step.
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            assertEquals(listOf("asset-1"), f.store.removeCalls)
        }

    @Test
    fun `FR-38 undo unhides the entry id and returns the card in place`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()

            // The card is back and the library write was reversed by exact id.
            val s = f.viewModel.uiState.value
            assertEquals("asset-1", s.current?.id)
            assertEquals(0, s.progress.reviewed)
            assertEquals(ToastMessage.Undone, s.toast)
            assertFalse(s.canUndo)
            assertEquals(listOf("asset-1" to true, "asset-1" to false), f.library.setHiddenCalls)
            assertTrue(f.library.hiddenAssetIds.isEmpty())
            // No decision row was ever written, so undo removed nothing.
            assertEquals(emptyList(), f.store.removeCalls)
            // The card returns in place (§5.6), not flying in from a side.
            f.viewModel.events.test {
                assertEquals(SwipeEvent.ReturnCardInPlace, awaitItem())
            }
        }

    @Test
    fun `FR-38 a failed unhide restores the undo step and keeps the card away`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()
            f.library.nextSetHiddenResult = HideResult.FAILED

            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()

            // The asset is still hidden, so the deck does not move and the step is
            // back on the stack — the user can retry the undo.
            val s = f.viewModel.uiState.value
            assertEquals("asset-2", s.current?.id)
            assertEquals(ToastMessage.SaveFailed, s.toast)
            assertTrue(s.canUndo)

            // A retry without the failure succeeds.
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            assertEquals(
                "asset-1",
                f.viewModel.uiState.value.current
                    ?.id,
            )
        }

    @Test
    fun `FR-38 undo is held while the hide write is in flight`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            f.library.setHiddenGate = gate

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            // Held: nothing popped, nothing unhidden (WP-7.2 rule, FR-38 extension).
            assertEquals(listOf("asset-1" to true), f.library.setHiddenCalls)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "asset-2",
                f.viewModel.uiState.value.current
                    ?.id,
            )
        }

    @Test
    fun `FR-38 hide clears a pending favorite confirm and undo restores it`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2, favoriteIds = setOf("asset-1"))
            advanceUntilIdle()

            // FR-22: a Delete on the favorite sits in the confirm, unrecorded.
            f.viewModel.onIntent(f.viewModel.delete())
            advanceUntilIdle()
            assertEquals(ToastMessage.FavoriteConfirm, f.viewModel.uiState.value.toast)
            assertEquals("asset-1", f.viewModel.uiState.value.pendingFavoriteDelete)

            // Hide clears the confirm at once and hides the card (addendum: the
            // Keep/Delete branches clear it on move — Hide must too).
            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()
            val hidden = f.viewModel.uiState.value
            assertEquals("asset-2", hidden.current?.id)
            assertNull(hidden.pendingFavoriteDelete)
            assertEquals(ToastMessage.Hidden, hidden.toast)

            // Undo brings the card back *with* the confirm it had.
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            val back = f.viewModel.uiState.value
            assertEquals("asset-1", back.current?.id)
            assertEquals(ToastMessage.FavoriteConfirm, back.toast)
            assertEquals("asset-1", back.pendingFavoriteDelete)
            // And the favorite is still a favorite: no DELETE was ever recorded.
            assertEquals(emptyList(), f.store.entries)
        }

    @Test
    fun `FR-38 hides count toward none of the FR-21 summary numbers`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Hide("asset-1"))
            advanceUntilIdle()
            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Hide("asset-3"))
            advanceUntilIdle()

            // The deck ran out on the second hide; the summary counts one keep and
            // no phantom entries for the two hides (FR-21, §7).
            val s = f.viewModel.uiState.value
            assertNull(s.current)
            val summary = assertNotNull(s.finished)
            assertEquals(1, summary.kept)
            assertEquals(0, summary.queued)
            assertEquals(0, summary.skipped)
            // But progress counted all three cards like decisions (FR-19).
            assertEquals(3, s.progress.reviewed)
        }

    @Test
    fun `FR-38 hidden assets are absent from the deck source`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // PhotoKit's default fetch view excludes hidden assets — the guarantee
            // the fake models by keeping hiddenAssetIds out of every read path.
            val library = FakeMediaLibrary(photos(3))
            val store = FakeDecisionStore(FakeClock(), FakeSessionSkips())
            library.setHidden("asset-2", hidden = true)

            val deck = buildDeckUseCase(library, store).invoke(SOURCE, MediaTypeFilter.ALL, SEED).ids

            assertEquals(listOf("asset-1", "asset-3"), deck)
            assertFalse(library.items(listOf("asset-2")).any { it.id == "asset-2" })
        }
}
