@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.usecase.FinishSessionUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.StartSessionUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.fakes.FakeMediaSharer
import dev.swipe.fakes.FakeVideoPreloader
import dev.swipe.fakes.buildDeckUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * WP-7.4 decision-integrity audit cases (report: docs/audits/2026-11-decision-audit.md):
 * the wrong-card guard seen as an integration, the undo paths the other suites do not
 * cover (KEEP undo leaving no row, undo after a failed write, undo after resume), the
 * resume contract (FR-20), and the FR-22 guards against confirming a different card.
 * Every case asserts no DELETE row exists for a kept or never-verded card.
 */
class SwipeViewModelAuditTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** A second screen over the same stores — the "process death" resume of FR-20. */
    private fun resumedScreen(f: Fixture) =
        SwipeViewModel(
            source = SOURCE,
            typeFilter = MediaTypeFilter.ALL,
            startSession = StartSessionUseCase(buildDeckUseCase(f.library, f.store), f.store, f.sessionStore),
            finishSession = FinishSessionUseCase(f.store, f.sessionStore),
            recordDecision = RecordDecisionUseCase(f.store),
            undoDecision = UndoDecisionUseCase(f.store),
            mediaLibrary = f.library,
            mediaLoader = FakeMediaLoader(),
            videoPreloader = FakeVideoPreloader(),
            mediaSharer = FakeMediaSharer(),
            mediaHider = f.library,
            decisions = f.store,
            seedProvider = { SEED },
        )

    @Test
    fun `FR-13 integration out of order decision intents are all dropped and nothing is recorded`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            val gate = CompletableDeferred<Unit>()
            f.store.gateNextRecord = gate

            f.viewModel.uiState.test {
                awaitDeckReady()

                // asset-1's KEEP is suspended on the gate: the head is still asset-1.
                f.viewModel.onIntent(f.viewModel.keep())
                advanceUntilIdle()
                // FR-38: the only emission the in-flight write makes is the `deciding`
                // toggle; the card has not moved.
                assertEquals("asset-1", awaitDecidingToggle().current?.id)
                expectNoEvents()

                // Stray intents from a racing UI naming cards that are not on
                // screen: every one is dropped by the FR-13 id guard, whatever the
                // verdict, while the first write is in flight.
                f.viewModel.onIntent(SwipeIntent.Decide("asset-2", Decision.DELETE))
                f.viewModel.onIntent(SwipeIntent.Decide("asset-3", Decision.KEEP))
                advanceUntilIdle()
                expectNoEvents()

                gate.complete(Unit)
                val after = awaitItem()
                assertEquals("asset-2", after.current?.id)
            }
            // The only write ever attempted is the one KEEP; no DELETE row exists.
            assertEquals(listOf(Triple("asset-1", Decision.KEEP, "session-1")), f.store.recordCalls)
            assertEquals(
                listOf(Triple("asset-1", Decision.KEEP, "session-1")),
                f.store.entries.map { Triple(it.id, it.decision, it.sessionId) },
            )
            assertTrue(
                f.store
                    .deleteQueue()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `FR-15 undo before a committed swipe decide arrives leaves no delete row and re-presents the card`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            // asset-1 is kept; asset-2 is on screen.
            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()
            assertEquals("asset-2", f.viewModel.currentId())

            // Raw-protocol race the UI can no longer produce (WP-9.10: DeckContent
            // holds an Undo tapped mid-fly-off and forwards it right after the
            // Decide, where the ViewModel queues it behind the write). Delivered in
            // the old order anyway, the Undo is processed first: it pops asset-1's
            // step; asset-2's late Decide then names a card that is no longer at the
            // head and is dropped by the FR-13 guard.
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Decide("asset-2", Decision.DELETE))
            advanceUntilIdle()

            // Nothing is stranded: the swipe is discarded, asset-1's row is removed,
            // asset-1 is on screen again, and no DELETE row was ever written.
            assertEquals(listOf("asset-1"), f.store.removeCalls)
            assertEquals(emptyList(), f.store.entries)
            assertTrue(
                f.store
                    .deleteQueue()
                    .first()
                    .isEmpty(),
            )
            assertEquals("asset-1", f.viewModel.currentId())
            assertFalse(f.viewModel.uiState.value.canUndo)
        }

    @Test
    fun `FR-15 undo of a KEEP leaves no decision row for the card`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()

            // Re-deciding the returned card for real: exactly one row exists for it,
            // the new DELETE. The undone KEEP left no row that could resurface as a
            // queued delete (FR-15, NFR-5).
            f.viewModel.onIntent(f.viewModel.delete())
            advanceUntilIdle()

            assertEquals(1, f.store.entries.count { it.id == "asset-1" })
            assertEquals(
                Decision.DELETE,
                f.store.entries
                    .single { it.id == "asset-1" }
                    .decision,
            )
            assertEquals(listOf("asset-1"), f.store.removeCalls)
            assertEquals(listOf("asset-1"), f.store.deleteQueue().first())
        }

    @Test
    fun `FR-15 nothing is undone after a failed write because the decision never reached the stack`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            f.store.failNextRecord = IllegalStateException("disk full")

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(f.viewModel.keep())
                awaitDecidingToggle() // FR-38: the write's toggle-up emission
                val failed = awaitItem()
                // NFR-7: the card stays, the failure surfaces.
                assertEquals(ToastMessage.SaveFailed, failed.toast)
                assertEquals("asset-1", failed.current?.id)
                assertFalse(failed.canUndo)
                assertFalse(failed.deciding)

                // Undo has nothing to undo — the failed decision never became a step —
                // so it removes nothing and the stored state stays empty.
                f.viewModel.onIntent(SwipeIntent.Undo)
                expectNoEvents()
            }
            assertEquals(emptyList(), f.store.removeCalls)
            assertEquals(emptyList(), f.store.entries)

            // A successful decide then undoes normally: exactly one removal.
            advanceUntilIdle()
            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()
            assertEquals(listOf("asset-1"), f.store.removeCalls)
            assertEquals(emptyList(), f.store.entries)
        }

    @Test
    fun `FR-15 undo after resume reports nothing to undo and records nothing`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()

            // "Process death": the in-memory undo stack does not survive; a resumed
            // screen must not rewind into the old session's decisions.
            val second = resumedScreen(f)
            advanceUntilIdle()
            assertEquals("asset-2", second.currentId())
            assertFalse(second.uiState.value.canUndo)

            second.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()

            assertEquals(emptyList(), f.store.removeCalls)
            assertEquals("asset-2", second.currentId())
            // The old session's KEEP row is untouched, and no DELETE appeared.
            assertEquals(
                listOf(Triple("asset-1", Decision.KEEP, "session-1")),
                f.store.entries.map { Triple(it.id, it.decision, it.sessionId) },
            )
            assertTrue(
                f.store
                    .deleteQueue()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `FR-20 resumed session restores the cursor and records no decisions on start`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(10)
            advanceUntilIdle()
            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()
            f.viewModel.onIntent(f.viewModel.skip())
            advanceUntilIdle()
            assertEquals("asset-3", f.viewModel.currentId())
            val recordsAfterFirst = f.store.recordCalls.toList()

            val second = resumedScreen(f)
            advanceUntilIdle()
            val s = second.uiState.value

            // Same card on screen, no store writes from the resume itself, and the
            // delete queue unchanged (FR-20): starting a screen records nothing.
            assertEquals("asset-3", s.current?.id)
            assertEquals(recordsAfterFirst, f.store.recordCalls)
            assertTrue(
                f.store
                    .deleteQueue()
                    .first()
                    .isEmpty(),
            )
            assertFalse(s.canUndo)
        }

    @Test
    fun `FR-22 confirming with the wrong id is ignored and keeps the dialog and the card`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3, favoriteIds = setOf("asset-1"))

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(f.viewModel.delete())
                val asked = awaitItem()
                assertEquals(ToastMessage.FavoriteConfirm, asked.toast)
                assertEquals("asset-1", asked.pendingFavoriteDelete)

                // A confirm naming a different card cannot act: the dialog and the
                // card stay, and nothing is recorded or queued (FR-22).
                f.viewModel.onIntent(SwipeIntent.ConfirmFavoriteDelete("asset-2"))
                expectNoEvents()
            }
            assertEquals(emptyList(), f.store.recordCalls)
            assertTrue(
                f.store
                    .deleteQueue()
                    .first()
                    .isEmpty(),
            )
            assertEquals("asset-1", f.viewModel.currentId())
        }

    @Test
    fun `FR-22 right swipe while the favorite confirm is up records KEEP not DELETE`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3, favoriteIds = setOf("asset-1"))
            advanceUntilIdle()

            f.viewModel.onIntent(f.viewModel.delete())
            advanceUntilIdle()
            assertEquals("asset-1", f.viewModel.uiState.value.pendingFavoriteDelete)

            // The user changes their mind and swipes the favorite right: KEEP lands,
            // and no DELETE row is created by the pending confirm.
            f.viewModel.onIntent(f.viewModel.keep())
            advanceUntilIdle()
            assertEquals(
                listOf(Triple("asset-1", Decision.KEEP, "session-1")),
                f.store.recordCalls,
            )
            assertEquals("asset-2", f.viewModel.currentId())

            // Even if the confirm is tapped after the card advanced, its guard
            // (pending id must still be the card on screen) drops it: the KEEP stays.
            f.viewModel.onIntent(SwipeIntent.ConfirmFavoriteDelete("asset-1"))
            advanceUntilIdle()
            assertEquals(1, f.store.recordCalls.size)
            assertEquals(
                Decision.KEEP,
                f.store.entries
                    .single()
                    .decision,
            )
            assertTrue(
                f.store
                    .deleteQueue()
                    .first()
                    .isEmpty(),
            )
        }
}
