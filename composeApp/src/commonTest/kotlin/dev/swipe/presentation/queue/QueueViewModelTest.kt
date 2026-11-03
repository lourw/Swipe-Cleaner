@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.queue

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeleteResult
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.usecase.CommitDeletionsUseCase
import dev.swipe.domain.usecase.PruneQueueUseCase
import dev.swipe.domain.usecase.RestoreFromQueueUseCase
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeHaptics
import dev.swipe.fakes.FakeMediaLibrary
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [QueueViewModel] (WP-3.1, FR-23–28, NFR-5): the newest-first queue with its
 * FR-28 prune on load, restore (FR-24), and the two-step delete — the in-app
 * confirm as the gate (NFR-5), one PhotoKit call per batch (FR-25), the three
 * outcomes with their events (FR-26, FR-27, §6.7).
 *
 * Queue order is always asset-N … asset-1 newest decision first, because rows
 * are recorded in that order and the fake store ties fixed-clock stamps to
 * newest-recorded first.
 */
class QueueViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-23 queue is ordered newest decision first`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()

            f.viewModel.uiState.test {
                val ready = awaitQueueReady()
                // Recorded asset-1 first, asset-3 last.
                assertEquals(listOf("asset-3", "asset-2", "asset-1"), ready.items)
            }
        }

    @Test
    fun `FR-28 queue load prunes ids that no longer exist`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3)
            // asset-2 was deleted in the Photos app while the app was away.
            f.library.delete(listOf("asset-2"))
            f.start()

            f.viewModel.uiState.test {
                val ready = awaitQueueReady()
                // The vanished id is gone from the list the screen first sees…
                assertEquals(listOf("asset-3", "asset-1"), ready.items)
            }
            // …and silently out of the store (FR-28): a remove, no toast, no error.
            assertEquals(listOf("asset-2"), f.store.removeCalls)
            assertEquals(listOf("asset-3", "asset-1"), f.store.deleteQueue().first())
        }

    @Test
    fun `FR-24 restore removes item from queue and records KEEP`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()

            f.viewModel.uiState.test {
                awaitQueueReady()

                f.viewModel.onIntent(QueueIntent.Restore("asset-2"))
                val after = awaitItem()

                assertEquals(listOf("asset-3", "asset-1"), after.items)
                assertNull(after.result)
            }
            val restoreCall = f.store.recordCalls.last()
            assertEquals(Triple("asset-2", Decision.KEEP, RestoreFromQueueUseCase.RESTORE_SESSION_ID), restoreCall)
            // The pending DELETE row became a KEEP: out of the queue, decided, and
            // the other two rows are untouched (FR-24).
            assertEquals(listOf("asset-3", "asset-1"), f.store.deleteQueue().first())
            assertTrue("asset-2" in f.store.decidedIds())
            assertEquals(
                Decision.KEEP,
                f.store.entries
                    .first { it.id == "asset-2" }
                    .decision,
            )
        }

    @Test
    fun `FR-24 open selects the item and restore closes the viewer with it`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()

            f.viewModel.uiState.test {
                awaitQueueReady()

                f.viewModel.onIntent(QueueIntent.Open("asset-2"))
                assertEquals("asset-2", awaitItem().selected)

                f.viewModel.onIntent(QueueIntent.Restore("asset-2"))
                // The viewer closes first (same dispatch as the restore), then the
                // queue re-emit drops the row.
                assertNull(awaitItem().selected)
                val after = awaitItem()
                assertEquals(listOf("asset-3", "asset-1"), after.items)
                assertNull(after.selected)
            }
        }

    @Test
    fun `FR-24 close dismisses the viewer and the confirm dialog`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2).start()

            f.viewModel.uiState.test {
                awaitQueueReady()

                f.viewModel.onIntent(QueueIntent.Open("asset-2"))
                assertEquals("asset-2", awaitItem().selected)

                f.viewModel.onIntent(QueueIntent.Close)
                val closed = awaitItem()
                assertNull(closed.selected)
                assertFalse(closed.confirming)
                // Nothing was deleted and the queue is intact.
                assertTrue(f.library.deleteCalls.isEmpty())
                assertEquals(2, closed.items.size)
            }
        }

    @Test
    fun `FR-24 open ignores an id the queue no longer has`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2).start()

            f.viewModel.uiState.test {
                awaitQueueReady()

                f.viewModel.onIntent(QueueIntent.Open("not-queued"))
                expectNoEvents()
                assertNull(f.viewModel.uiState.value.selected)
            }
        }

    @Test
    fun `FR-24 restore of an id the queue never held records nothing`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2).start()

            f.viewModel.uiState.test {
                awaitQueueReady()

                f.viewModel.onIntent(QueueIntent.Restore("not-queued"))
                expectNoEvents()
                // No KEEP row for an asset the user never queued (mirrors open()).
                assertEquals(0, f.store.recordCalls.count { it.first == "not-queued" })
                assertEquals(listOf("asset-2", "asset-1"), f.viewModel.uiState.value.items)
            }
        }

    @Test
    fun `NFR-5 deleting with no in-app confirmation does nothing`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()

            f.viewModel.uiState.test {
                awaitQueueReady()

                // RequestDelete only raises the dialog: PhotoKit is never reached…
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                val asking = awaitItem()
                assertTrue(asking.confirming)
                assertTrue(f.library.deleteCalls.isEmpty())

                // …and ConfirmDelete with no dialog up (never requested, or since
                // closed) is ignored.
                f.viewModel.onIntent(QueueIntent.CancelConfirm)
                assertFalse(awaitItem().confirming)
                f.viewModel.onIntent(QueueIntent.ConfirmDelete)
                expectNoEvents()
                assertTrue(f.library.deleteCalls.isEmpty())
                assertNull(f.viewModel.uiState.value.result)

                // Cancelling the dialog deletes nothing either (NFR-5).
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                awaitItem()
                f.viewModel.onIntent(QueueIntent.CancelConfirm)
                val cancelled = awaitItem()
                assertFalse(cancelled.confirming)
                assertTrue(f.library.deleteCalls.isEmpty())
                assertEquals(3, cancelled.items.size)
            }
            // No store call of any kind beyond the seeded queue: nothing marked
            // deleted, nothing restored.
            assertTrue(f.store.markDeletedCalls.isEmpty())
            assertTrue(f.store.removeCalls.isEmpty())
        }

    @Test
    fun `FR-25 commit calls MediaLibrary delete exactly once with every queued id`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()
            f.viewModel.events.test {
                val eventTurbine = this
                f.viewModel.uiState.test {
                    awaitQueueReady()

                    f.viewModel.onIntent(QueueIntent.RequestDelete)
                    awaitItem()
                    f.viewModel.onIntent(QueueIntent.ConfirmDelete)
                    awaitStateThat { !it.deleting && it.items.isEmpty() && it.result is QueueDeleteResult.Deleted }

                    // One call, the whole batch, newest decision first (FR-25).
                    assertEquals(1, f.library.deleteCalls.size)
                    assertEquals(listOf("asset-3", "asset-2", "asset-1"), f.library.deleteCalls.single())
                    assertEquals(QueueEvent.ShowDeleteSuccess(3), eventTurbine.awaitItem())
                }
            }
        }

    @Test
    fun `FR-26 confirmed delete marks items DELETED and empties the queue`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()

            f.viewModel.uiState.test {
                awaitQueueReady()

                f.viewModel.onIntent(QueueIntent.RequestDelete)
                awaitItem()
                f.viewModel.onIntent(QueueIntent.ConfirmDelete)
                val done = awaitStateThat { !it.deleting && it.items.isEmpty() && it.result is QueueDeleteResult.Deleted }

                assertEquals(listOf(listOf("asset-3", "asset-2", "asset-1")), f.store.markDeletedCalls)
                assertEquals(QueueDeleteResult.Deleted(3), done.result)
                assertFalse(done.confirming)
                // FR-26's success haptic fires with the success event (§5.6).
                assertEquals(listOf(FakeHaptics.Event.NOTIFICATION_SUCCESS), f.haptics.events)
            }
            assertEquals(
                setOf(Decision.DELETED),
                f.store.entries
                    .map { it.decision }
                    .toSet(),
            )
        }

    @Test
    fun `FR-27 cancelling the iOS dialog leaves the queue exactly as it was`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()
            f.library.nextDeleteResult = DeleteResult.Cancelled
            val queueBefore = f.store.deleteQueue().first()
            val entriesBefore = f.store.entries

            f.viewModel.events.test {
                val eventTurbine = this
                f.viewModel.uiState.test {
                    awaitQueueReady()

                    f.viewModel.onIntent(QueueIntent.RequestDelete)
                    awaitItem()
                    f.viewModel.onIntent(QueueIntent.ConfirmDelete)
                    val after = awaitStateThat { !it.deleting }

                    // FR-27: no error, no result, queue intact…
                    assertNull(after.result)
                    assertFalse(after.confirming)
                    assertEquals(listOf("asset-3", "asset-2", "asset-1"), after.items)
                }
                // …and nothing was emitted: the cancel is silent.
                eventTurbine.expectNoEvents()
            }
            // No store calls at all: the rows are byte-identical to before.
            assertTrue(f.store.markDeletedCalls.isEmpty())
            assertTrue(f.store.removeCalls.isEmpty())
            assertEquals(queueBefore, f.store.deleteQueue().first())
            assertEquals(entriesBefore, f.store.entries)
            assertTrue(f.haptics.events.isEmpty())
        }

    @Test
    fun `6-7 delete failure shows a toast and keeps the queue`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()
            f.library.nextDeleteResult = DeleteResult.Failed("iCloud sync failed")
            val entriesBefore = f.store.entries

            f.viewModel.events.test {
                val eventTurbine = this
                f.viewModel.uiState.test {
                    awaitQueueReady()

                    f.viewModel.onIntent(QueueIntent.RequestDelete)
                    awaitItem()
                    f.viewModel.onIntent(QueueIntent.ConfirmDelete)
                    val failed = awaitStateThat { !it.deleting && it.result is QueueDeleteResult.Failed }

                    // The queue is unchanged and the failure surfaces as a toast
                    // event carrying PhotoKit's message (§6.7).
                    assertEquals(listOf("asset-3", "asset-2", "asset-1"), failed.items)
                    assertEquals(QueueDeleteResult.Failed("iCloud sync failed"), failed.result)
                    assertFalse(failed.confirming)
                    assertEquals(QueueEvent.ShowDeleteError("iCloud sync failed"), eventTurbine.awaitItem())
                }
            }
            assertTrue(f.store.markDeletedCalls.isEmpty())
            assertEquals(entriesBefore, f.store.entries)
            assertTrue(f.haptics.events.isEmpty())
        }

    @Test
    fun `6-7 a delete error clears on the next request and keeps the queue live`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2).start()
            f.library.nextDeleteResult = DeleteResult.Failed("disk full")

            f.viewModel.uiState.test {
                awaitQueueReady()
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                awaitItem()
                f.viewModel.onIntent(QueueIntent.ConfirmDelete)
                val failed = awaitStateThat { !it.deleting && it.result is QueueDeleteResult.Failed }
                assertEquals(QueueDeleteResult.Failed("disk full"), failed.result)

                // Retrying starts clean: the stale error is gone while confirming.
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                val asking = awaitItem()
                assertNull(asking.result)
                assertTrue(asking.confirming)
                // The queue is still there to retry.
                assertEquals(listOf("asset-2", "asset-1"), asking.items)
            }
        }

    @Test
    fun `FR-25 a second confirm while deleting does not call delete again`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2).start()
            advanceUntilIdle()

            // Confirm, then re-issue intents without yielding: [QueueUiState.deleting]
            // is set synchronously, so every later intent is ignored while the commit
            // coroutine is queued (FR-25: one call per batch).
            f.viewModel.onIntent(QueueIntent.RequestDelete)
            f.viewModel.onIntent(QueueIntent.ConfirmDelete)
            f.viewModel.onIntent(QueueIntent.ConfirmDelete)
            f.viewModel.onIntent(QueueIntent.RequestDelete)
            advanceUntilIdle()

            assertEquals(1, f.library.deleteCalls.size)
            val state = f.viewModel.uiState.value
            assertFalse(state.deleting)
            assertEquals(QueueDeleteResult.Deleted(2), state.result)
        }

    @Test
    fun `6-7 intents are ignored while a commit is in flight`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()
            advanceUntilIdle()

            f.viewModel.onIntent(QueueIntent.RequestDelete)
            f.viewModel.onIntent(QueueIntent.ConfirmDelete)
            // The commit coroutine is queued behind this one; deleting is already true.
            f.viewModel.onIntent(QueueIntent.Open("asset-2"))
            f.viewModel.onIntent(QueueIntent.Restore("asset-2"))
            f.viewModel.onIntent(QueueIntent.CancelConfirm)
            advanceUntilIdle()

            // The in-flight batch commits; the ignored restore left its row queued.
            assertEquals(listOf(listOf("asset-3", "asset-2", "asset-1")), f.store.markDeletedCalls)
            assertEquals(1, f.library.deleteCalls.size)
            val state = f.viewModel.uiState.value
            assertNull(state.selected)
            assertFalse(state.confirming)
        }

    @Test
    fun `6-7 commit deletes only ids still queued when the list changed under the dialog`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(3).start()

            f.viewModel.uiState.test {
                awaitQueueReady()
                f.viewModel.onIntent(QueueIntent.RequestDelete)
                awaitItem()

                // asset-1 is restored in the Photos app (pruned from the store) after
                // the dialog opened: the token may carry it, the queue does not.
                f.store.remove("asset-1", "elsewhere")
                f.viewModel.onIntent(QueueIntent.ConfirmDelete)
                val done =
                    awaitStateThat { !it.deleting && it.items.isEmpty() && it.result is QueueDeleteResult.Deleted }

                // Only the two still-queued ids reach PhotoKit (NFR-5).
                assertEquals(listOf(listOf("asset-3", "asset-2")), f.library.deleteCalls)
                assertEquals(listOf(listOf("asset-3", "asset-2")), f.store.markDeletedCalls)
                assertEquals(QueueDeleteResult.Deleted(2), done.result)
            }
        }

    @Test
    fun `6-7 an empty queue cannot raise the confirm dialog`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(0).start()

            f.viewModel.uiState.test {
                // An empty queue never changes the state, so there is one emission.
                val ready = awaitItem()
                assertTrue(ready.items.isEmpty())

                f.viewModel.onIntent(QueueIntent.RequestDelete)
                expectNoEvents()
                assertFalse(f.viewModel.uiState.value.confirming)
                assertTrue(f.library.deleteCalls.isEmpty())
            }
        }

    @Test
    fun `6-7 a failing restore keeps the item queued and emits RestoreFailed`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = Fixture(2).start()
            advanceUntilIdle()
            f.store.failNextRecord = IllegalStateException("disk full")

            f.viewModel.events.test {
                f.viewModel.onIntent(QueueIntent.Restore("asset-2"))
                assertEquals(QueueEvent.RestoreFailed, awaitItem())
                expectNoEvents()
            }
            // The item stays queued; nothing else moved (NFR-7's convention).
            assertEquals(listOf("asset-2", "asset-1"), f.viewModel.uiState.value.items)
            assertTrue(f.store.markDeletedCalls.isEmpty())
            assertTrue(f.library.deleteCalls.isEmpty())
        }

    /** Consume the initial (pre-queue) emission and return the first queue-built state. */
    private suspend fun ReceiveTurbine<QueueUiState>.awaitQueueReady(): QueueUiState {
        awaitItem()
        return awaitItem()
    }

    /**
     * Consume emissions until one satisfies [predicate]: a commit settles across two
     * coroutines (the commit itself and the queue collector), so the terminal state
     * can arrive in more than one update.
     */
    private suspend fun ReceiveTurbine<QueueUiState>.awaitStateThat(predicate: (QueueUiState) -> Boolean): QueueUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    /**
     * Wires [QueueViewModel] over the shared fakes with [count] queued DELETE rows
     * (asset-1 … asset-[count], asset-1 recorded first, so queue order is
     * asset-[count] … asset-1). Pass 0 for an empty queue.
     *
     * [start] seeds the store first, so the ViewModel's FR-28 prune on load sees it.
     */
    private class Fixture(
        private val count: Int,
    ) {
        val clock = FakeClock()
        val store = FakeDecisionStore(clock)
        val library = FakeMediaLibrary(photos(count))
        val haptics = FakeHaptics()
        lateinit var viewModel: QueueViewModel
            private set

        suspend fun start(): Fixture {
            for (i in 1..count) {
                store.record("asset-$i", Decision.DELETE, SEED_SESSION_ID)
            }
            viewModel =
                QueueViewModel(
                    commitDeletions = CommitDeletionsUseCase(library, store),
                    pruneQueue = PruneQueueUseCase(library, store),
                    restoreFromQueue = RestoreFromQueueUseCase(store),
                    decisions = store,
                    haptics = haptics,
                )
            return this
        }

        private companion object {
            /** The seeded rows' session; no test asserts on it. */
            const val SEED_SESSION_ID = "session-1"
        }
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
