@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.queue

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.StorageEstimate
import dev.swipe.domain.usecase.CommitDeletionsUseCase
import dev.swipe.domain.usecase.PruneQueueUseCase
import dev.swipe.domain.usecase.RestoreFromQueueUseCase
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeMediaSizing
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * FR-34 (WP-8.4): while the delete confirmation is up it shows what the queue
 * would free — the [MediaSizing] run behind it is a side-channel that gates
 * nothing (Risk R3): the estimate lands when it can, a throw is just
 * `Unavailable`, cancelling the dialog cancels the run, and the delete path
 * (FR-25, NFR-5) is byte-for-byte what it was without sizing.
 *
 * Split out of [QueueViewModelTest] (detekt LargeClass); the queue mechanics
 * themselves live there. Queue order is asset-N … asset-1 newest decision
 * first, as in the sibling suite.
 */
class QueueViewModelSizingTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-34 raising the dialog estimates the current queue and shows the total`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()
            f.sizing.estimate = StorageEstimate.Complete(totalBytes = 1_200_000_000L)

            f.viewModel.uiState.test {
                awaitQueueReady()
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                val asking = awaitStateThat { it.confirming && it.deleteSize != null }
                assertEquals(StorageEstimate.Complete(totalBytes = 1_200_000_000L), asking.deleteSize)
            }
            // Exactly the queue as it stands, newest decision first (FR-25's order).
            assertEquals(listOf(listOf("asset-3", "asset-2", "asset-1")), f.sizing.estimateCalls)
        }

    @Test
    fun `FR-34 a partial estimate arrives as a lower bound and an unavailable one as nothing`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2).start()
            f.sizing.estimate = StorageEstimate.Partial(totalBytes = 850_000_000L)

            f.viewModel.uiState.test {
                awaitQueueReady()
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                val partial = awaitStateThat { it.confirming && it.deleteSize != null }
                assertEquals(StorageEstimate.Partial(totalBytes = 850_000_000L), partial.deleteSize)

                // A run where nothing could be read is Unavailable, not an error:
                // the dialog simply has no line to fill in (R3).
                f.sizing.estimate = StorageEstimate.Unavailable
                f.viewModel.onIntent(QueueIntent.CancelConfirm)
                awaitStateThat { !it.confirming } // settles the cancel before re-requesting
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                val asking = awaitStateThat { it.confirming && it.deleteSize != null }
                assertEquals(StorageEstimate.Unavailable, asking.deleteSize)
                assertTrue(asking.confirming)
            }
        }

    @Test
    fun `FR-34 a sizing failure is Unavailable and keeps the dialog up without an event`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2).start()
            f.sizing.throwOnEstimate = IllegalStateException("PHAssetResource unreadable")

            f.viewModel.events.test {
                val eventTurbine = this
                f.viewModel.uiState.test {
                    awaitQueueReady()
                    f.viewModel.onIntent(QueueIntent.RequestDelete)
                    val asking = awaitStateThat { it.confirming && it.deleteSize != null }
                    assertEquals(StorageEstimate.Unavailable, asking.deleteSize)
                    assertTrue(asking.confirming)
                    // The throw never surfaces as a user-facing event (R3).
                    eventTurbine.expectNoEvents()
                }
            }
        }

    @Test
    fun `FR-34 a confirmed delete never waits for a still-loading estimate`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()
            // The estimate hangs: the dialog is up with no line yet (R3's window).
            val gate = CompletableDeferred<Unit>()
            f.sizing.gate = gate

            f.viewModel.uiState.test {
                awaitQueueReady()
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                val asking = awaitItem()
                assertTrue(asking.confirming)
                assertNull(asking.deleteSize)
                advanceUntilIdle() // the estimate run starts and hangs on the gate…
                assertEquals(1, f.sizing.estimateCalls.size)

                // …and the delete goes ahead right on top of it (R3).
                f.viewModel.onIntent(QueueIntent.ConfirmDelete)
                // tiles.isEmpty() too: the commit's terminal update lands before the
                // queue collector rebuilds the (now empty) tiles, so the settled
                // state is the last of the commit's emissions.
                val done =
                    awaitStateThat {
                        !it.deleting && it.items.isEmpty() && it.tiles.isEmpty() && it.result is QueueDeleteResult.Deleted
                    }

                // The batch commits exactly as the no-sizing tests see it.
                assertEquals(listOf(listOf("asset-3", "asset-2", "asset-1")), f.library.deleteCalls)
                assertEquals(listOf(listOf("asset-3", "asset-2", "asset-1")), f.store.markDeletedCalls)
                assertEquals(QueueDeleteResult.Deleted(3), done.result)
                assertNull(done.deleteSize)
            }
            // The estimate that missed its moment is gone: letting its gate
            // through afterwards fills nothing in — the job was cancelled with
            // the dialog, and a stale line never lands (R3).
            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(f.viewModel.uiState.value.deleteSize)
        }

    @Test
    fun `FR-34 cancelling the dialog cancels the in-flight estimate and re-requesting restarts it`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2).start()
            val gate = CompletableDeferred<Unit>()
            f.sizing.gate = gate

            f.viewModel.uiState.test {
                awaitQueueReady()
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                awaitItem()
                advanceUntilIdle() // the estimate run starts and hangs on the gate
                assertEquals(1, f.sizing.estimateCalls.size)
                f.viewModel.onIntent(QueueIntent.CancelConfirm)
                val cancelled = awaitStateThat { !it.confirming }
                assertNull(cancelled.deleteSize)

                // The hanging estimate was cancelled with the dialog: letting it
                // through afterwards fills nothing in — no stale line can land
                // on the closed dialog, and none on the screen behind it (R3).
                gate.complete(Unit)
                advanceUntilIdle()
                assertNull(f.viewModel.uiState.value.deleteSize)

                // Reopening starts a fresh run: the second request sizes the queue again.
                f.sizing.gate = null
                f.sizing.estimate = StorageEstimate.Complete(totalBytes = 2_000L)
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                val asking = awaitStateThat { it.confirming && it.deleteSize != null }
                assertEquals(StorageEstimate.Complete(totalBytes = 2_000L), asking.deleteSize)
            }
            assertEquals(2, f.sizing.estimateCalls.size)
        }

    @Test
    fun `FR-34 an empty queue never asks for an estimate`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(0).start()

            f.viewModel.onIntent(QueueIntent.RequestDelete)
            advanceUntilIdle()
            assertTrue(f.sizing.estimateCalls.isEmpty())
            assertFalse(f.viewModel.uiState.value.confirming)
        }

    /** Consume the initial (pre-queue) emission and return the first queue-built state. */
    private suspend fun ReceiveTurbine<QueueUiState>.awaitQueueReady(): QueueUiState {
        awaitItem()
        return awaitItem()
    }

    /** Consume emissions until one satisfies [predicate] (see the sibling suite). */
    private suspend fun ReceiveTurbine<QueueUiState>.awaitStateThat(predicate: (QueueUiState) -> Boolean): QueueUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    /**
     * The same wiring as [QueueViewModelTest]'s fixture: [count] queued DELETE
     * rows (asset-1 recorded first), the ViewModel over the shared fakes with
     * [FakeMediaSizing] in the sizing slot.
     */
    private class Fixture(
        private val count: Int,
    ) {
        val clock = FakeClock()
        val store = FakeDecisionStore(clock)
        val library = FakeMediaLibrary(photos(count))
        val sizing = FakeMediaSizing()
        lateinit var viewModel: QueueViewModel
            private set

        suspend fun start(): Fixture {
            for (i in 1..count) {
                store.record("asset-$i", Decision.DELETE, "session-1")
            }
            viewModel =
                QueueViewModel(
                    commitDeletions = CommitDeletionsUseCase(library, store),
                    pruneQueue = PruneQueueUseCase(library, store),
                    restoreFromQueue = RestoreFromQueueUseCase(store),
                    mediaSizing = sizing,
                    decisions = store,
                    mediaLibrary = library,
                )
            return this
        }

        private companion object {
            /** Queue items: asset-1 … asset-[count], asset-1 created first. */
            fun photos(count: Int): List<MediaItem> =
                (1..count).map { i ->
                    MediaItem(
                        id = "asset-$i",
                        type = MediaType.PHOTO,
                        createdAt = Instant.fromEpochMilliseconds(i * 1000L),
                        durationMs = null,
                        widthPx = 120,
                        heightPx = 160,
                        isFavorite = false,
                        isScreenshot = false,
                        burstId = null,
                        isCloudOnly = null,
                    )
                }
        }
    }
}
