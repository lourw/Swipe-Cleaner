@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.domain.usecase

import dev.swipe.domain.model.Decision
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
 * FR-28 pruning (WP-3.1): queued ids PhotoKit no longer returns are dropped from the
 * store on every queue load, silently, and the existence probe stays paged at 20 ids
 * per `items` call (§6.5 step 2, NFR-4 / AGENTS.md rule 6).
 */
class PruneQueueUseCaseTest {
    private val clock = FakeClock()
    private val store = FakeDecisionStore(clock)

    /**
     * The library holds `asset-1` … `asset-30` plus two doomed ids; tests delete the
     * doomed ones through the fake to simulate the Photos app (a
     * [dev.swipe.domain.model.DeleteResult.Deleted] drops them from the library).
     */
    private val library =
        FakeMediaLibrary(
            (1..30).map { photo("asset-$it") } + photo("gone-1") + photo("gone-2"),
        )
    private val prune = PruneQueueUseCase(library, store)

    @Test
    fun `FR-28 prune removes queued ids the library no longer returns`() =
        runTest {
            queue("asset-1", "gone-1", "asset-2", "gone-2")
            // Simulate both vanished assets being deleted in the Photos app.
            library.delete(listOf("gone-1", "gone-2"))

            // Pruned ids come back in queue order (newest decision first, FR-23).
            val pruned = prune()

            assertEquals(listOf("gone-2", "gone-1"), pruned)
            assertEquals(listOf("gone-2", "gone-1"), store.removeCalls)
            // The survivors keep their FR-23 order; the vanished rows are gone.
            assertEquals(listOf("asset-2", "asset-1"), store.deleteQueue().first())
            assertTrue(store.entries.none { it.id in setOf("gone-1", "gone-2") })
        }

    @Test
    fun `FR-28 prune probes existence in pages of 20 ids`() =
        runTest {
            val many = (1..25).map { "asset-$it" }
            queue(*many.toTypedArray())

            // The library has all 25, so nothing is pruned; only the paging shows.
            val pruned = prune()

            assertTrue(pruned.isEmpty())
            // §6.5 step 2: `items` takes at most 20 ids per call.
            assertEquals(
                // Queue order is newest first, so the chunks are reversed.
                listOf(many.reversed().take(20), many.reversed().drop(20)),
                library.itemsCalls,
            )
        }

    @Test
    fun `6-7 prune skips the library when the queue is empty`() =
        runTest {
            assertTrue(prune().isEmpty())
            assertTrue(library.itemsCalls.isEmpty())
        }

    @Test
    fun `6-7 prune stays silent when a removal fails`() =
        runTest {
            queue("asset-1", "gone-1")
            library.delete(listOf("gone-1"))
            store.failNextRemove = IllegalStateException("disk full")

            // No throw escapes, and the id is still reported as missing (FR-28:
            // pruning is silent; the row survives to the next load).
            assertEquals(listOf("gone-1"), prune())
            assertEquals(listOf("gone-1", "asset-1"), store.deleteQueue().first())
        }

    /** Records [ids] as pending DELETEs, one clock tick apart, oldest first. */
    private suspend fun queue(vararg ids: String) {
        for (id in ids) {
            store.record(id, Decision.DELETE, "session-1")
            clock.advanceBy(1.seconds)
        }
    }

    private fun photo(id: String) =
        MediaItem(
            id = id,
            type = MediaType.PHOTO,
            createdAt = Instant.fromEpochMilliseconds(id.filter { it.isDigit() }.toLong() * 1000L),
            durationMs = null,
            widthPx = 120,
            heightPx = 160,
            isFavorite = false,
            isScreenshot = false,
            burstId = null,
            isCloudOnly = null,
        )
}
