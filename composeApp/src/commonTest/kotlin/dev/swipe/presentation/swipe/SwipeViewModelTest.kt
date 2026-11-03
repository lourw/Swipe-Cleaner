@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import dev.swipe.data.DecisionStore
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.Progress
import dev.swipe.domain.model.SessionSummary
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeMediaLoader
import dev.swipe.fakes.FakeSessionSkips
import dev.swipe.fakes.FakeSessionStore
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The WP-1.8 core of [SwipeViewModel] (§6.5 steps 2–3 and 5): the record-before-advance
 * decision flow (FR-12–14, NFR-7), progress (FR-19), metadata paging, and the prefetch
 * window. The deck is built from a non-shuffled source (§6.5: only `All` shuffles, and
 * the shuffle is covered in WP-1.6), so with date-descending metadata the deck order is
 * always asset-1, asset-2, … — fixed seeds and virtual time throughout (§8.2).
 */
class SwipeViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-12 keep records KEEP then advances`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)

            f.viewModel.uiState.test {
                val ready = awaitDeckReady()
                assertEquals("asset-1", ready.current?.id)
                assertEquals("asset-2", ready.next?.id)

                f.viewModel.onIntent(SwipeIntent.Keep)

                val after = awaitItem()
                assertEquals("asset-2", after.current?.id)
                assertEquals("asset-3", after.next?.id)
                assertEquals(1, after.progress.reviewed)
            }
            // The KEEP was persisted for this session before the index moved (FR-12, FR-20).
            assertEquals(listOf(Triple("asset-1", Decision.KEEP, "session-1")), f.store.recordCalls)
            // …and the resume position was saved (FR-20).
            assertEquals(listOf("session-1" to 1), f.sessionStore.updatePositionCalls)
            // The seed is stored on the session row so the order survives process death (§6.5 step 1).
            assertEquals(listOf(Triple(SOURCE, MediaTypeFilter.ALL, SEED)), f.sessionStore.createCalls)
        }

    @Test
    fun `FR-13 delete records DELETE then advances`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(SwipeIntent.Delete)

                val after = awaitItem()
                assertEquals("asset-2", after.current?.id)
                // The queue badge tracks DecisionStore.deleteQueue() live (FR-10, FR-23).
                assertEquals(1, awaitItem().queueCount)
            }
            assertEquals(listOf(Triple("asset-1", Decision.DELETE, "session-1")), f.store.recordCalls)
        }

    @Test
    fun `FR-14 skip records SKIP for this session only`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2)

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(SwipeIntent.Skip)

                assertEquals("asset-2", awaitItem().current?.id)
            }
            assertEquals(listOf(Triple("asset-1", Decision.SKIP, "session-1")), f.store.recordCalls)
            // SKIP goes to session_skip, never to the decision table (§7 comment)…
            assertTrue(f.store.entries.none { it.id == "asset-1" })
            // …carrying this session, so the item can appear in later sessions (FR-14).
            assertEquals(setOf("asset-1"), f.sessionStore.skippedIds("session-1"))
            assertTrue(f.store.decidedIds().none { it == "asset-1" })
        }

    @Test
    fun `NFR-7 failed write does not advance and reverts card`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            f.store.failNextRecord = IllegalStateException("disk full")

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(SwipeIntent.Keep)

                val failed = awaitItem()
                // The card stays exactly where it was, with a toast and a revert event.
                assertEquals("asset-1", failed.current?.id)
                assertEquals(0, failed.progress.reviewed)
                assertEquals(ToastMessage.SaveFailed, failed.toast)
                assertEquals(1, failed.revertToken)
            }
            // Nothing moved: no position save, and the deck head is unchanged.
            assertEquals(emptyList(), f.sessionStore.updatePositionCalls)
            assertEquals(
                "asset-1",
                f.viewModel.uiState.value.current
                    ?.id,
            )
        }

    @Test
    fun `NFR-7 decision is persisted before index changes`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val clock = FakeClock()
            val skips = FakeSessionSkips()
            val backing = FakeDecisionStore(clock, skips)
            lateinit var viewModel: SwipeViewModel
            // Snapshot the UI's reviewed count at the exact moment record() runs.
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
                    decisions = store,
                    sessionStore = FakeSessionStore(clock, skips),
                    seedProvider = { SEED },
                )

            viewModel.uiState.test {
                awaitDeckReady()

                viewModel.onIntent(SwipeIntent.Keep)

                assertEquals("asset-2", awaitItem().current?.id)
            }
            // When record() ran the state still showed reviewed = 0; the index only
            // changed after the write returned (NFR-7), and the write did happen.
            assertEquals(listOf(0), store.reviewedAtRecord)
            assertEquals(listOf(Triple("asset-1", Decision.KEEP, "session-1")), backing.recordCalls)
        }

    @Test
    fun `NFR-7 rapid double tap does not skip an undecided card`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)

            f.viewModel.uiState.test {
                assertEquals("asset-1", awaitDeckReady().current?.id)

                // Two taps dispatched back-to-back, before either write completes:
                // both capture asset-1, but only one advance may happen.
                f.viewModel.onIntent(SwipeIntent.Keep)
                f.viewModel.onIntent(SwipeIntent.Keep)
                advanceUntilIdle()

                // Exactly one emission: the stale advance is discarded, and asset-2 —
                // the card a double tap used to skip — is current and undecided.
                val after = awaitItem()
                assertEquals("asset-2", after.current?.id)
                assertEquals(1, after.progress.reviewed)
                expectNoEvents()
            }
            // Both writes targeted asset-1 (the idempotent upsert), nothing for asset-2.
            assertEquals(2, f.store.recordCalls.count { it.first == "asset-1" })
            assertTrue(f.store.recordCalls.none { it.first == "asset-2" })
            // The resume position moved by exactly one (FR-20).
            assertEquals(listOf("session-1" to 1), f.sessionStore.updatePositionCalls)
        }

    @Test
    fun `FR-19 progress counts reviewed over total`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2)

            f.viewModel.uiState.test {
                assertEquals(Progress(0, 2), awaitDeckReady().progress)

                f.viewModel.onIntent(SwipeIntent.Keep)
                assertEquals(Progress(1, 2), awaitItem().progress)

                f.viewModel.onIntent(SwipeIntent.Skip)
                // Deck exhausted → current is null and the summary is set (FR-21, §6.6).
                val done = awaitItem()
                assertEquals(Progress(2, 2), done.progress)
                assertNull(done.current)
                assertNull(done.next)
            }
        }

    @Test
    fun `6-5 metadata loads in pages of 20`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(45)

            f.viewModel.uiState.test {
                awaitDeckReady()
            }
            // Start loads exactly one page of 20 (§6.5 step 2).
            assertEquals(listOf((1..20).map { "asset-$it" }), f.library.itemsCalls)

            // Index 14 is not yet within 5 of the loaded end (14 + 5 < 20).
            repeat(14) {
                f.viewModel.onIntent(SwipeIntent.Keep)
                advanceUntilIdle()
            }
            assertEquals(1, f.library.itemsCalls.size)

            // Index 15 is (15 + 5 >= 20): the next page of 20 loads.
            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            assertEquals(2, f.library.itemsCalls.size)
            assertEquals((21..40).map { "asset-$it" }, f.library.itemsCalls[1])

            // Index 35 pulls the last (short) page.
            repeat(20) {
                f.viewModel.onIntent(SwipeIntent.Keep)
                advanceUntilIdle()
            }
            assertEquals(3, f.library.itemsCalls.size)
            assertEquals((41..45).map { "asset-$it" }, f.library.itemsCalls[2])
        }

    @Test
    fun `6-5 prefetch requests current plus next 3`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(6)

            f.viewModel.uiState.test {
                awaitDeckReady()
            }
            // §6.5 step 3: the current item plus the next three are requested up front.
            assertEquals(listOf(listOf("asset-1", "asset-2", "asset-3", "asset-4")), f.loader.prefetchCalls)
            assertEquals(emptyList(), f.loader.cancelPrefetchCalls)

            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            // The window slid: asset-5 joined, asset-1 was cancelled (§6.4 cancelPrefetch).
            assertEquals(
                listOf("asset-1", "asset-2", "asset-3", "asset-4", "asset-5"),
                listOf(
                    f.loader.prefetchCalls[0][0],
                    f.loader.prefetchCalls[1][0],
                    f.loader.prefetchCalls[1][1],
                    f.loader.prefetchCalls[1][2],
                    f.loader.prefetchCalls[1][3],
                ),
            )
            assertEquals(listOf(listOf("asset-1")), f.loader.cancelPrefetchCalls)

            // Walking off the deck's end: the window shrinks and every stale id is cancelled.
            repeat(5) {
                f.viewModel.onIntent(SwipeIntent.Keep)
                advanceUntilIdle()
            }
            assertEquals(
                listOf(listOf("asset-1"), listOf("asset-2"), listOf("asset-3"), listOf("asset-4"), listOf("asset-5"), listOf("asset-6")),
                f.loader.cancelPrefetchCalls,
            )
        }

    @Test
    fun `6-5 leaving the deck cancels the still-warm prefetch window`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(6)

            f.viewModel.uiState.test {
                awaitDeckReady()
            }
            // The warm window is asset-1 … asset-4 (§6.5 step 3).
            f.viewModel.onCleared()
            assertEquals(listOf(listOf("asset-1", "asset-2", "asset-3", "asset-4")), f.loader.cancelPrefetchCalls)
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
    fun `FR-22 deleting a favorite asks for confirmation before queueing`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3, favoriteIds = setOf("asset-1"))

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(SwipeIntent.Delete)
                val asked = awaitItem()
                // The card stays put and the confirm names it (§6.6 pendingFavoriteDelete).
                assertEquals("asset-1", asked.current?.id)
                assertEquals(0, asked.progress.reviewed)
                assertEquals(ToastMessage.FavoriteConfirm, asked.toast)
                assertEquals("asset-1", asked.pendingFavoriteDelete)
            }
            // Nothing was recorded or queued yet (FR-22).
            assertEquals(emptyList(), f.store.recordCalls)
            assertTrue(f.store.entries.none { it.decision == Decision.DELETE })

            // "Delete anyway": now the DELETE is recorded and the deck advances.
            f.viewModel.onIntent(SwipeIntent.ConfirmFavoriteDelete)
            advanceUntilIdle()
            val after = f.viewModel.uiState.value
            assertEquals("asset-2", after.current?.id)
            assertNull(after.pendingFavoriteDelete)
            assertNull(after.toast)
            assertEquals(listOf(Triple("asset-1", Decision.DELETE, "session-1")), f.store.recordCalls)
            assertEquals(1, after.queueCount)
        }

    @Test
    fun `FR-22 dismissing favorite confirm queues nothing`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3, favoriteIds = setOf("asset-1"))

            f.viewModel.uiState.test {
                awaitDeckReady()

                f.viewModel.onIntent(SwipeIntent.Delete)
                assertEquals(ToastMessage.FavoriteConfirm, awaitItem().toast)

                f.viewModel.onIntent(SwipeIntent.DismissToast)
                val cleared = awaitItem()
                assertNull(cleared.toast)
                assertNull(cleared.pendingFavoriteDelete)
                // The favorite is still up, undecided.
                assertEquals("asset-1", cleared.current?.id)
                assertEquals(0, cleared.progress.reviewed)
            }
            assertEquals(emptyList(), f.store.recordCalls)
            assertTrue(f.store.entries.none { it.decision == Decision.DELETE })
        }

    @Test
    fun `FR-16 mute toggles and persists across cards`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)

            f.viewModel.uiState.test {
                // Videos start muted (§5.7).
                assertTrue(awaitDeckReady().muted)

                f.viewModel.onIntent(SwipeIntent.ToggleMute)
                assertFalse(awaitItem().muted)

                // The setting survives card changes for the session's lifetime.
                f.viewModel.onIntent(SwipeIntent.Keep)
                val after = awaitItem()
                assertEquals("asset-2", after.current?.id)
                assertFalse(after.muted)

                f.viewModel.onIntent(SwipeIntent.ToggleMute)
                assertTrue(awaitItem().muted)
            }
        }

    @Test
    fun `FR-21 exhausted deck emits session summary with counts`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Delete)
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Skip)
            advanceUntilIdle()

            val s = f.viewModel.uiState.value
            // Counts come from the stored rows (FR-21), so they cover the whole session.
            assertEquals(SessionSummary(kept = 1, queued = 1, skipped = 1), s.finished)
            assertNull(s.current)
            assertNull(s.next)
            assertEquals(Progress(3, 3), s.progress)
            // The undo stack ends with the session, and the session row is ended.
            assertFalse(s.canUndo)
            assertEquals(listOf("session-1"), f.sessionStore.endCalls)
        }

    @Test
    fun `6-5 resumed session keeps shuffle order and position`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val clock = FakeClock()
            val skips = FakeSessionSkips()
            val store = FakeDecisionStore(clock, skips)
            val sessionStore = FakeSessionStore(clock, skips)
            val library = FakeMediaLibrary(photos(10))

            // `All` shuffles with the stored seed (§6.5), so this exercises the real rebuild.
            fun viewModel() =
                SwipeViewModel(
                    source = DeckSource.All,
                    typeFilter = MediaTypeFilter.ALL,
                    buildDeck = BuildDeckUseCase(library, store),
                    recordDecision = RecordDecisionUseCase(store),
                    undoDecision = UndoDecisionUseCase(store),
                    mediaLibrary = library,
                    mediaLoader = FakeMediaLoader(),
                    decisions = store,
                    sessionStore = sessionStore,
                    seedProvider = { SEED },
                )

            val first = viewModel()
            advanceUntilIdle()
            first.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            first.onIntent(SwipeIntent.Skip)
            advanceUntilIdle()
            first.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            val expectedCurrent =
                first.uiState.value.current
                    ?.id
            val expectedNext =
                first.uiState.value.next
                    ?.id
            assertEquals(1, sessionStore.createCalls.size)

            // "Process death": a fresh ViewModel over the same stores resumes, not restarts.
            val second = viewModel()
            advanceUntilIdle()
            val s = second.uiState.value
            // Same shuffle order (the seed) and back on the same card: the rebuild
            // already subtracted the stored keeps and this session's skip, leaving
            // seven cards, and the three disposed ones still count (FR-14, FR-19, FR-20).
            assertEquals(expectedCurrent, s.current?.id)
            assertEquals(expectedNext, s.next?.id)
            assertEquals(Progress(3, 10), s.progress)
            assertEquals(1, sessionStore.createCalls.size)

            // Continuing walks the same remaining order.
            second.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            assertEquals(
                expectedNext,
                second.uiState.value.current
                    ?.id,
            )
        }

    @Test
    fun `6-5 a source mismatch starts a fresh session instead of resuming`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            assertEquals(1, f.sessionStore.createCalls.size)

            // SOURCE here is Smart(Screenshots) while the open session is a fresh
            // Smart(Screenshots) deck — same source and filter, so it resumes.
            val resuming =
                SwipeViewModel(
                    source = SOURCE,
                    typeFilter = MediaTypeFilter.ALL,
                    buildDeck = BuildDeckUseCase(f.library, f.store),
                    recordDecision = RecordDecisionUseCase(f.store),
                    undoDecision = UndoDecisionUseCase(f.store),
                    mediaLibrary = f.library,
                    mediaLoader = FakeMediaLoader(),
                    decisions = f.store,
                    sessionStore = f.sessionStore,
                    seedProvider = { SEED },
                )
            advanceUntilIdle()
            assertEquals(1, f.sessionStore.createCalls.size)

            // A different source cannot resume it, so a new session row is created.
            SwipeViewModel(
                source = DeckSource.All,
                typeFilter = MediaTypeFilter.ALL,
                buildDeck = BuildDeckUseCase(f.library, f.store),
                recordDecision = RecordDecisionUseCase(f.store),
                undoDecision = UndoDecisionUseCase(f.store),
                mediaLibrary = f.library,
                mediaLoader = FakeMediaLoader(),
                decisions = f.store,
                sessionStore = f.sessionStore,
                seedProvider = { SEED },
            )
            advanceUntilIdle()
            assertEquals(2, f.sessionStore.createCalls.size)
        }

    @Test
    fun `events are delivered to one collector and never replayed`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Undo)
            advanceUntilIdle()

            // §6.6 events policy: the card-return command rides a Channel, so the
            // buffered event reaches exactly one collector…
            f.viewModel.events.test {
                assertEquals(SwipeEvent.ReturnCard(Decision.KEEP), awaitItem())
            }
            // …and is consumed — a late collector (a recomposition) sees nothing.
            f.viewModel.events.test {
                expectNoEvents()
            }
        }

    @Test
    fun `6-5 a failed session open degrades to an empty deck with a toast`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            f.sessionStore.failNextCreate = IllegalStateException("db gone")
            advanceUntilIdle()

            // No crash, no deck — just the empty screen and an error toast (§6.5 step 1).
            val s = f.viewModel.uiState.value
            assertEquals(ToastMessage.StartFailed, s.toast)
            assertNull(s.current)
            assertNull(s.finished)
        }

    /**
     * Wraps a [DecisionStore] and records the value of [reviewedNow] at the moment
     * [record] / [remove] is called — the strict "persisted before the index changes"
     * probe (NFR-7), mirrored for undo (FR-15): if the ViewModel moved first, the
     * snapshot would show it.
     */
    private class OrderRecordingDecisionStore(
        private val delegate: DecisionStore,
        private val reviewedNow: () -> Int,
    ) : DecisionStore by delegate {
        /** The reviewed count seen by each [record] call, oldest first. */
        val reviewedAtRecord = mutableListOf<Int>()

        /** The reviewed count seen by each [remove] call, oldest first. */
        val reviewedAtRemove = mutableListOf<Int>()

        override suspend fun record(
            id: String,
            decision: Decision,
            sessionId: String,
        ) {
            reviewedAtRecord += reviewedNow()
            delegate.record(id, decision, sessionId)
        }

        override suspend fun remove(
            id: String,
            sessionId: String,
        ) {
            reviewedAtRemove += reviewedNow()
            delegate.remove(id, sessionId)
        }
    }

    /** Wires the ViewModel over the shared fakes; deck order is asset-1 … asset-[itemCount]. */
    private class Fixture(
        itemCount: Int,
        seed: Long = SEED,
        favoriteIds: Set<String> = emptySet(),
    ) {
        val clock = FakeClock()
        val skips = FakeSessionSkips()
        val store = FakeDecisionStore(clock, skips)
        val sessionStore = FakeSessionStore(clock, skips)
        val library = FakeMediaLibrary(photos(itemCount, favoriteIds))
        val loader = FakeMediaLoader()
        val viewModel =
            SwipeViewModel(
                source = SOURCE,
                typeFilter = MediaTypeFilter.ALL,
                buildDeck = BuildDeckUseCase(library, store),
                recordDecision = RecordDecisionUseCase(store),
                undoDecision = UndoDecisionUseCase(store),
                mediaLibrary = library,
                mediaLoader = loader,
                decisions = store,
                sessionStore = sessionStore,
                seedProvider = { seed },
            )
    }

    private companion object {
        /** The fixed shuffle seed; unused by the source, stored on the session (§6.5 step 1). */
        const val SEED = 1234L

        /** A non-shuffled source so the deck order is deterministic (§6.5). */
        val SOURCE: DeckSource = DeckSource.Smart(SmartFilter.SCREENSHOTS)

        /** [count] screenshots; asset-1 newest, so deck order is asset-1, asset-2, … */
        fun photos(
            count: Int,
            favoriteIds: Set<String> = emptySet(),
        ): List<MediaItem> =
            (1..count).map { i ->
                MediaItem(
                    id = "asset-$i",
                    type = MediaType.PHOTO,
                    createdAt = Instant.fromEpochMilliseconds((count - i + 1L) * 1000L),
                    durationMs = null,
                    widthPx = 120,
                    heightPx = 160,
                    isFavorite = "asset-$i" in favoriteIds,
                    isScreenshot = true,
                    burstId = null,
                    isCloudOnly = null,
                )
            }
    }
}

/** Consume the initial (pre-deck) emission and return the first deck-built state. */
private suspend fun ReceiveTurbine<SwipeUiState>.awaitDeckReady(): SwipeUiState {
    awaitItem()
    return awaitItem()
}
