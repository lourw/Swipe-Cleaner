@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.domain.usecase

import dev.swipe.domain.model.Decision
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * FR-24 restore (WP-3.1): recording `KEEP` under the synthetic restore session id
 * replaces the pending DELETE row, so the item leaves the queue and never reappears
 * in a deck (it is a decided id from then on).
 */
class RestoreFromQueueUseCaseTest {
    private val clock = FakeClock()
    private val store = FakeDecisionStore(clock)
    private val restore = RestoreFromQueueUseCase(store)

    @Test
    fun `FR-24 restore removes item from queue and records KEEP`() =
        runTest {
            queue("a", "b", "c") // queue order: c, b, a

            restore("b")

            // KEEP is recorded under the synthetic restore session (§7 has no FK on
            // session_id, so a real session id is not needed)…
            assertEquals(listOf(Triple("b", Decision.KEEP, RestoreFromQueueUseCase.RESTORE_SESSION_ID)), store.recordCalls.takeLast(1))
            // …the item is out of the queue, in FR-23 order for the rest…
            assertEquals(listOf("c", "a"), store.deleteQueue().first())
            // …and it is a decided id, so decks keep excluding it (FR-9).
            assertTrue("b" in store.decidedIds())
            // The other queued items keep their pending DELETEs.
            assertEquals(
                mapOf("a" to Decision.DELETE, "b" to Decision.KEEP, "c" to Decision.DELETE),
                store.entries.associate { it.id to it.decision },
            )
        }

    @Test
    fun `FR-24 restore of a kept-elsewhere id rewrites it to the restore session`() =
        runTest {
            queue("a")

            restore("a")
            restore("a") // restoring twice is a harmless upsert of the same KEEP row

            assertEquals(1, store.entries.size)
            assertEquals(Decision.KEEP, store.entries.single().decision)
            assertEquals(RestoreFromQueueUseCase.RESTORE_SESSION_ID, store.entries.single().sessionId)
            assertTrue(store.deleteQueue().first().isEmpty())
        }

    /** Records [ids] as pending DELETEs, one clock tick apart, oldest first. */
    private suspend fun queue(vararg ids: String) {
        for (id in ids) {
            store.record(id, Decision.DELETE, "session-1")
            clock.advanceBy(1.seconds)
        }
    }
}
