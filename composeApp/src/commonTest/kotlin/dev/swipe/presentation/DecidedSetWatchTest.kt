package dev.swipe.presentation

import dev.swipe.domain.model.Decision
import dev.swipe.fakes.FakeDecisionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-30 (§6.5 Counts): the shared resume-time check behind Home (and, in
 * WP-follow-up, Albums and By-month) re-counting — a change is reported once,
 * the first check only records, and a failed read answers "no change" while
 * keeping the old snapshot so the next resume retries (§6.8).
 */
class DecidedSetWatchTest {
    @Test
    fun `FR-30 the watch reports a change once and the first check only records`() =
        runTest {
            val store = FakeDecisionStore()
            store.record("asset-1", Decision.KEEP, "s1")
            val watch = DecidedSetWatch(store)

            // The startup load already counted against this set: no reload.
            assertFalse(watch.changedSinceLastCheck())

            store.record("asset-2", Decision.KEEP, "s1")
            assertTrue(watch.changedSinceLastCheck())
            // Reported once — the next check sees no further change.
            assertFalse(watch.changedSinceLastCheck())
        }

    @Test
    fun `FR-30 a failed read reports no change`() =
        runTest {
            val store = FakeDecisionStore()
            store.record("asset-1", Decision.KEEP, "s1")
            val watch = DecidedSetWatch(store)
            assertFalse(watch.changedSinceLastCheck())

            // A change lands, but the read fails: false now, and the old
            // snapshot is kept so the next resume still sees it (§6.8).
            store.record("asset-2", Decision.KEEP, "s1")
            store.failNextDecidedIds = IllegalStateException("store unreadable")
            assertFalse(watch.changedSinceLastCheck())
            assertTrue(watch.changedSinceLastCheck())
        }
}
