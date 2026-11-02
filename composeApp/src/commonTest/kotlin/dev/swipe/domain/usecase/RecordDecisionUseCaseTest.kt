package dev.swipe.domain.usecase

import dev.swipe.domain.model.Decision
import dev.swipe.fakes.FakeDecisionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FR-12–14 recording and NFR-7 failure path: the use case is a thin pass-through to
 * [dev.swipe.data.DecisionStore.record] and lets store failures escape.
 */
class RecordDecisionUseCaseTest {
    @Test
    fun `FR-12 record writes the verdict to the store`() =
        runTest {
            val store = FakeDecisionStore()

            RecordDecisionUseCase(store)("a", Decision.KEEP, "s1")

            assertEquals(listOf(Triple("a", Decision.KEEP, "s1")), store.recordCalls)
            assertEquals(setOf("a"), store.decidedIds())
        }

    @Test
    fun `NFR-7 record rethrows store failure`() =
        runTest {
            val store = FakeDecisionStore()
            store.failNextRecord = IllegalStateException("disk full")

            val error =
                assertFailsWith<IllegalStateException> {
                    RecordDecisionUseCase(store)("a", Decision.DELETE, "s1")
                }

            assertEquals("disk full", error.message)
            // Nothing else happened: no retry, no other store touched (NFR-7).
            assertEquals(1, store.recordCalls.size)
            assertEquals(emptySet(), store.decidedIds())
        }
}
