@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.domain.usecase

import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeleteResult
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The §6.7 commit core (WP-3.1): one `MediaLibrary.delete` per confirmed batch with
 * exactly the ids that are in both the [InAppDeleteConfirmation] and the current
 * queue, and the three outcome branches — Deleted marks DELETED (FR-26), Cancelled
 * and Failed touch no store (FR-27, §6.7).
 *
 * The queue rows are recorded one clock tick apart so the FR-23 newest-first order
 * is deterministic. `InAppDeleteConfirmation`'s constructor is `internal` (§6.7);
 * `commonTest` is an associated compilation of the same module, which is why these
 * tests can build the token — production code outside the module cannot (see the
 * type-level note in `QueueViewModelTest`'s NFR-5 test).
 */
class CommitDeletionsUseCaseTest {
    private val clock = FakeClock()
    private val store = FakeDecisionStore(clock)
    private val library = FakeMediaLibrary(photos("a", "b", "c", "d"))
    private val commit = CommitDeletionsUseCase(library, store)

    @Test
    fun `FR-25 commit calls MediaLibrary delete exactly once with every queued id`() =
        runTest {
            queue("a", "b", "c") // queue order (newest first): c, b, a

            val outcome = commit(InAppDeleteConfirmation(listOf("c", "b", "a")))

            assertEquals(CommitOutcome.Success(deleted = 3), outcome)
            // Exactly one call — one performChanges, one iOS dialog (FR-25) — carrying
            // every queued id, in the order the user saw the list.
            assertEquals(listOf(listOf("c", "b", "a")), library.deleteCalls)
        }

    @Test
    fun `FR-27 cancelling the iOS dialog leaves the queue exactly as it was`() =
        runTest {
            queue("a", "b")
            library.nextDeleteResult = DeleteResult.Cancelled
            val queueBefore = store.deleteQueue().first()
            val entriesBefore = store.entries

            val outcome = commit(InAppDeleteConfirmation(listOf("b", "a")))

            assertEquals(CommitOutcome.Cancelled, outcome)
            assertEquals(listOf(listOf("b", "a")), library.deleteCalls)
            // No store call of any kind: the rows are still pending DELETEs in the
            // same order, and nothing was marked deleted (FR-27).
            assertTrue(store.markDeletedCalls.isEmpty())
            assertEquals(queueBefore, store.deleteQueue().first())
            assertEquals(entriesBefore, store.entries)
        }

    @Test
    fun `FR-26 confirmed delete marks items DELETED and empties the queue`() =
        runTest {
            queue("a", "b", "c")

            val outcome = commit(InAppDeleteConfirmation(listOf("c", "b", "a")))

            assertEquals(CommitOutcome.Success(deleted = 3), outcome)
            // Exactly the deleted batch is flipped (FR-26)…
            assertEquals(listOf(listOf("c", "b", "a")), store.markDeletedCalls)
            assertEquals(
                setOf("c", "b", "a"),
                store.entries
                    .filter { it.decision == Decision.DELETED }
                    .map { it.id }
                    .toSet(),
            )
            // …and the queue runs empty.
            assertTrue(store.deleteQueue().first().isEmpty())
        }

    @Test
    fun `6-7 failed commit reports the message and stores nothing`() =
        runTest {
            queue("a", "b")
            library.nextDeleteResult = DeleteResult.Failed("iCloud sync failed")
            val queueBefore = store.deleteQueue().first()

            val outcome = commit(InAppDeleteConfirmation(listOf("b", "a")))

            assertEquals(CommitOutcome.Error("iCloud sync failed"), outcome)
            // The §6.7 error branch touches no store either; the queue is unchanged.
            assertTrue(store.markDeletedCalls.isEmpty())
            assertEquals(queueBefore, store.deleteQueue().first())
        }

    @Test
    fun `6-7 commit deletes only ids present in both the confirmation and the current queue`() =
        runTest {
            queue("a", "b", "c") // queue: c, b, a
            // The dialog showed c and a; b was restored and x queued while it was up,
            // and d was never in the queue at all.
            store.record("x", Decision.DELETE, "session-1")
            val confirmation = InAppDeleteConfirmation(listOf("c", "a", "d"))

            val outcome = commit(confirmation)

            assertEquals(CommitOutcome.Success(deleted = 2), outcome)
            // Only the intersection reaches PhotoKit, in confirmation order; b and x
            // stay queued for the next confirmed batch (NFR-5).
            assertEquals(listOf(listOf("c", "a")), library.deleteCalls)
            assertEquals(listOf(listOf("c", "a")), store.markDeletedCalls)
            assertEquals(listOf("x", "b"), store.deleteQueue().first())
        }

    @Test
    fun `6-7 commit whose whole confirmation vanished deletes nothing`() =
        runTest {
            queue("a", "b")
            // Everything the dialog showed was restored elsewhere before the confirm
            // landed: the intersection is empty.
            store.record("a", Decision.KEEP, "restore")
            store.record("b", Decision.KEEP, "restore")

            val outcome = commit(InAppDeleteConfirmation(listOf("b", "a")))

            assertEquals(CommitOutcome.Success(deleted = 0), outcome)
            // No iOS dialog for an empty batch, and no store write.
            assertTrue(library.deleteCalls.isEmpty())
            assertTrue(store.markDeletedCalls.isEmpty())
        }

    /** Records [ids] as pending DELETEs, one clock tick apart, oldest first. */
    private suspend fun queue(vararg ids: String) {
        for (id in ids) {
            store.record(id, Decision.DELETE, "session-1")
            clock.advanceBy(1.seconds)
        }
    }

    private fun photos(vararg ids: String): List<MediaItem> =
        ids.mapIndexed { i, id ->
            MediaItem(
                id = id,
                type = MediaType.PHOTO,
                createdAt = Instant.fromEpochMilliseconds((ids.size - i) * 1000L),
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
