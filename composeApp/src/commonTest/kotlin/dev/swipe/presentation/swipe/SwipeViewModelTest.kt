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
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
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
                // Deck exhausted → current is null; the summary screen is WP-2.2 (§6.6).
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

    /**
     * Wraps a [DecisionStore] and records the value of [reviewedNow] at the moment
     * [record] is called — the strict "persisted before the index changes" probe
     * (NFR-7): if the ViewModel advanced first, the snapshot would show it.
     */
    private class OrderRecordingDecisionStore(
        private val delegate: DecisionStore,
        private val reviewedNow: () -> Int,
    ) : DecisionStore by delegate {
        /** The reviewed count seen by each [record] call, oldest first. */
        val reviewedAtRecord = mutableListOf<Int>()

        override suspend fun record(
            id: String,
            decision: Decision,
            sessionId: String,
        ) {
            reviewedAtRecord += reviewedNow()
            delegate.record(id, decision, sessionId)
        }
    }

    /** Wires the ViewModel over the shared fakes; deck order is asset-1 … asset-[itemCount]. */
    private class Fixture(
        itemCount: Int,
        seed: Long = SEED,
    ) {
        val clock = FakeClock()
        val skips = FakeSessionSkips()
        val store = FakeDecisionStore(clock, skips)
        val sessionStore = FakeSessionStore(clock, skips)
        val library = FakeMediaLibrary(photos(itemCount))
        val loader = FakeMediaLoader()
        val viewModel =
            SwipeViewModel(
                source = SOURCE,
                typeFilter = MediaTypeFilter.ALL,
                buildDeck = BuildDeckUseCase(library, store),
                recordDecision = RecordDecisionUseCase(store),
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
        fun photos(count: Int): List<MediaItem> =
            (1..count).map { i ->
                MediaItem(
                    id = "asset-$i",
                    type = MediaType.PHOTO,
                    createdAt = Instant.fromEpochMilliseconds((count - i + 1L) * 1000L),
                    durationMs = null,
                    widthPx = 120,
                    heightPx = 160,
                    isFavorite = false,
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
