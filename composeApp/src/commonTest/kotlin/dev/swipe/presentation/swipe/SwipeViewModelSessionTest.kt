@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.swipe

import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.Progress
import dev.swipe.domain.model.SessionSummary
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
 * The session half of [SwipeViewModel]: the favorite delete confirm (FR-22), mute
 * (FR-16), the session summary (FR-21) including the empty-deck close (WP-7.3),
 * session resume and its fresh-start mismatches
 * (FR-20, §6.5 step 1), and the one-shot events channel (§6.6). Fixed seeds and
 * virtual time throughout (§8.2).
 */
class SwipeViewModelSessionTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
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
    fun `FR-22 a second confirm during the in-flight write queues nothing extra`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3, favoriteIds = setOf("asset-1"))
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Delete)
            val asked = f.viewModel.uiState.value
            assertEquals(ToastMessage.FavoriteConfirm, asked.toast)
            assertEquals("asset-1", asked.pendingFavoriteDelete)

            // Two confirms dispatched back-to-back, while the first write is still
            // in flight: the first clears the pending id synchronously, so the
            // second finds nothing pending and records nothing (FR-22).
            f.viewModel.onIntent(SwipeIntent.ConfirmFavoriteDelete)
            f.viewModel.onIntent(SwipeIntent.ConfirmFavoriteDelete)
            advanceUntilIdle()

            // Exactly one DELETE — asset-2, the card behind the favorite, is never
            // queued without its own confirm.
            assertEquals(listOf(Triple("asset-1", Decision.DELETE, "session-1")), f.store.recordCalls)
            val s = f.viewModel.uiState.value
            assertEquals("asset-2", s.current?.id)
            assertNull(s.pendingFavoriteDelete)
            assertEquals(1, s.queueCount)
        }

    @Test
    fun `FR-22 confirming the favorite on the last card counts it as queued in the summary`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(1, favoriteIds = setOf("asset-1"))
            advanceUntilIdle()

            f.viewModel.onIntent(SwipeIntent.Delete)
            assertEquals(ToastMessage.FavoriteConfirm, f.viewModel.uiState.value.toast)
            assertEquals(emptyList(), f.store.recordCalls)

            // The confirmed DELETE runs the deck out, so the summary is built with
            // it counted as queued (FR-22, FR-21).
            f.viewModel.onIntent(SwipeIntent.ConfirmFavoriteDelete)
            advanceUntilIdle()

            val s = f.viewModel.uiState.value
            assertEquals(SessionSummary(kept = 0, queued = 1, skipped = 0), s.finished)
            assertNull(s.current)
            assertNull(s.next)
            assertEquals(listOf(Triple("asset-1", Decision.DELETE, "session-1")), f.store.recordCalls)
            assertEquals(listOf("session-1"), f.sessionStore.endCalls)
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
    fun `FR-21 opening an empty deck shows the empty state and ends the session row`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(0)
            advanceUntilIdle()

            val s = f.viewModel.uiState.value
            // §5.8 empty state: no card, and no summary — a deck that opens empty
            // never ran out, so FR-21 has nothing to summarize.
            assertNull(s.current)
            assertNull(s.next)
            assertNull(s.finished)
            assertNull(s.toast)
            assertEquals(Progress(0, 0), s.progress)
            assertFalse(s.canUndo)
            // The zero-review session is over as soon as it opened: no decisions,
            // and the row is ended instead of staying open forever (FR-20).
            assertEquals(emptyList(), f.store.recordCalls)
            assertEquals(1, f.sessionStore.createCalls.size)
            assertEquals(listOf("session-1"), f.sessionStore.endCalls)
            assertNotNull(
                f.sessionStore.stored
                    .single()
                    .endedAt,
            )
        }

    @Test
    fun `FR-21 a session with cards is not ended before the deck runs out`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2)
            advanceUntilIdle()

            // Neither at start nor mid-deck is the row touched: it stays open so a
            // reopen resumes the review (FR-20).
            assertTrue(f.sessionStore.endCalls.isEmpty())
            assertNull(
                f.sessionStore.stored
                    .single()
                    .endedAt,
            )
            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            assertTrue(f.sessionStore.endCalls.isEmpty())

            // Only running the deck out ends it — exactly once, with the summary.
            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            val s = f.viewModel.uiState.value
            assertEquals(SessionSummary(kept = 2, queued = 0, skipped = 0), s.finished)
            assertEquals(listOf("session-1"), f.sessionStore.endCalls)
        }

    @Test
    fun `FR-20 FR-21 resuming a zero-review session with an empty deck ends the row without a summary`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // A row created just before process death, nothing decided: the reopen
            // resumes it (FR-20), the deck rebuilds empty, and the zero-review
            // session closes right away — empty state, no summary, row ended (FR-21).
            val clock = FakeClock()
            val skips = FakeSessionSkips()
            val store = FakeDecisionStore(clock, skips)
            val sessionStore = FakeSessionStore(clock, skips)
            val library = FakeMediaLibrary(emptyList())
            sessionStore.create(SOURCE, MediaTypeFilter.ALL, SEED)

            val reopened =
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
                    sessionStore = sessionStore,
                    seedProvider = { SEED },
                )
            advanceUntilIdle()

            val s = reopened.uiState.value
            assertNull(s.current)
            assertNull(s.finished)
            assertEquals(Progress(0, 0), s.progress)
            // Resumed, not recreated — and the resumed row ends.
            assertEquals(1, sessionStore.createCalls.size)
            assertEquals(listOf("session-1"), sessionStore.endCalls)
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
                    videoPreloader = FakeVideoPreloader(),
                    mediaSharer = FakeMediaSharer(),
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
    fun `WP-6-4 resume after a mid-session commit counts committed rows in the reviewed base`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(10)
            advanceUntilIdle()

            // Keep one, queue two, then commit the queue (FR-26): PhotoKit
            // drops the assets and the rows flip DELETE → DELETED.
            f.viewModel.onIntent(SwipeIntent.Keep)
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Delete)
            advanceUntilIdle()
            f.viewModel.onIntent(SwipeIntent.Delete)
            advanceUntilIdle()
            val committed = listOf("asset-2", "asset-3")
            f.library.delete(committed)
            f.store.markDeleted(committed)

            // "Process death": a fresh screen resumes the same session. The
            // committed rows match neither KEEP nor pending DELETE any more, so
            // the old kept + queued base under-counted by 2; `reviewed` counts
            // every session row, and the base lands on 3 of the original 10.
            val second =
                SwipeViewModel(
                    source = SOURCE,
                    typeFilter = MediaTypeFilter.ALL,
                    buildDeck = BuildDeckUseCase(f.library, f.store),
                    recordDecision = RecordDecisionUseCase(f.store),
                    undoDecision = UndoDecisionUseCase(f.store),
                    mediaLibrary = f.library,
                    mediaLoader = FakeMediaLoader(),
                    videoPreloader = FakeVideoPreloader(),
                    mediaSharer = FakeMediaSharer(),
                    decisions = f.store,
                    sessionStore = f.sessionStore,
                    seedProvider = { SEED },
                )
            advanceUntilIdle()
            val s = second.uiState.value
            assertEquals(Progress(3, 10), s.progress)
            // The deck rebuilt from what is left: asset-1 kept, asset-2/3 gone.
            assertEquals("asset-4", s.current?.id)
        }

    @Test
    fun `FR-20 FR-21 FR-26 resuming a fully committed session retries the summary`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // The durable state as process death strikes: session-1 open, both cards
            // queued (FR-26) and the queue then committed, so the rows read DELETED
            // and the assets are gone from the library — but the FR-21 summary was
            // never delivered and the row was never ended.
            val clock = FakeClock()
            val skips = FakeSessionSkips()
            val store = FakeDecisionStore(clock, skips)
            val sessionStore = FakeSessionStore(clock, skips)
            val library = FakeMediaLibrary(photos(2))
            val sessionId = sessionStore.create(SOURCE, MediaTypeFilter.ALL, SEED)
            store.record("asset-1", Decision.DELETE, sessionId)
            store.record("asset-2", Decision.DELETE, sessionId)
            library.delete(listOf("asset-1", "asset-2"))
            store.markDeleted(listOf("asset-1", "asset-2"))

            // A fresh screen resumes the session (FR-20): the rebuild is empty
            // because nothing is left in the library, and only `reviewed` counting
            // the committed DELETED rows keeps the base above zero — with the old
            // kept + queued base this reopens as an empty screen instead of
            // retrying the summary.
            val resumed =
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
                    sessionStore = sessionStore,
                    seedProvider = { SEED },
                )
            advanceUntilIdle()

            val s = resumed.uiState.value
            // The retried FR-21 summary: the committed rows are no longer queued.
            assertEquals(SessionSummary(kept = 0, queued = 0, skipped = 0), s.finished)
            assertNull(s.current)
            assertNull(s.next)
            // The reviewed base counts both committed rows: 2 of 2, deck rebuilt empty.
            assertEquals(Progress(2, 2), s.progress)
            // Retrying the summary ends the session row, and no new row was created.
            assertEquals(listOf("session-1"), sessionStore.endCalls)
            assertEquals(1, sessionStore.createCalls.size)
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
                    videoPreloader = FakeVideoPreloader(),
                    mediaSharer = FakeMediaSharer(),
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
                videoPreloader = FakeVideoPreloader(),
                mediaSharer = FakeMediaSharer(),
                decisions = f.store,
                sessionStore = f.sessionStore,
                seedProvider = { SEED },
            )
            advanceUntilIdle()
            assertEquals(2, f.sessionStore.createCalls.size)
        }

    @Test
    fun `6-5 a type filter mismatch starts a fresh session instead of resuming`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            advanceUntilIdle()
            assertEquals(1, f.sessionStore.createCalls.size)

            // Same source, but the open session is the ALL filter: the Photos
            // screen cannot resume it, so a new session row is created (§6.5 step 1).
            SwipeViewModel(
                source = SOURCE,
                typeFilter = MediaTypeFilter.PHOTOS,
                buildDeck = BuildDeckUseCase(f.library, f.store),
                recordDecision = RecordDecisionUseCase(f.store),
                undoDecision = UndoDecisionUseCase(f.store),
                mediaLibrary = f.library,
                mediaLoader = FakeMediaLoader(),
                videoPreloader = FakeVideoPreloader(),
                mediaSharer = FakeMediaSharer(),
                decisions = f.store,
                sessionStore = f.sessionStore,
                seedProvider = { SEED },
            )
            advanceUntilIdle()
            assertEquals(2, f.sessionStore.createCalls.size)
            assertEquals(
                MediaTypeFilter.PHOTOS,
                f.sessionStore.createCalls[1]
                    .second,
            )
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
}
