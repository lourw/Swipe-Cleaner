package dev.swipe.domain.usecase

import dev.swipe.domain.model.Decision
import dev.swipe.fakes.FakeDecisionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FR-15 undo: the use case clears the persisted verdict through
 * [dev.swipe.data.DecisionStore.remove] (decision row or session skip).
 */
class UndoDecisionUseCaseTest {
    @Test
    fun `FR-15 undo clears the stored decision`() =
        runTest {
            val store = FakeDecisionStore()
            store.record("a", Decision.KEEP, "s1")

            UndoDecisionUseCase(store)("a", "s1")

            assertEquals(listOf("a"), store.removeCalls)
            assertEquals(emptySet(), store.decidedIds())
        }

    @Test
    fun `FR-15 undo rethrows store failure`() =
        runTest {
            val store = FakeDecisionStore()
            store.failNextRemove = IllegalStateException("disk full")

            assertFailsWith<IllegalStateException> {
                UndoDecisionUseCase(store)("a", "s1")
            }
        }
}
