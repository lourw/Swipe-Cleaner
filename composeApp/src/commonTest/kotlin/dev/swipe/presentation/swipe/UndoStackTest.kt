package dev.swipe.presentation.swipe

import dev.swipe.domain.model.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FR-15 [UndoStack] in isolation: push order and the 50-cap, the single-flight
 * removal (pop / settle / restore), and the end-of-session clear. The ViewModel-side
 * sequences over these primitives live in `SwipeViewModelUndoTest`.
 */
class UndoStackTest {
    private fun entry(n: Int) = UndoEntry("asset-$n", Decision.KEEP, n)

    @Test
    fun `FR-15 a fresh stack is empty and pops nothing`() {
        val stack = UndoStack()
        assertFalse(stack.isNotEmpty())
        assertNull(stack.popForRemoval())
    }

    @Test
    fun `FR-15 pushes become undoable newest first and clear empties the stack`() {
        val stack = UndoStack()
        stack.push(entry(1))
        stack.push(entry(2))
        assertTrue(stack.isNotEmpty())
        assertEquals(2, stack.popForRemoval()?.index)
        stack.removalSettled()
        assertEquals(1, stack.popForRemoval()?.index)
        stack.removalSettled()
        assertFalse(stack.isNotEmpty())

        stack.push(entry(3))
        stack.clear()
        assertFalse(stack.isNotEmpty())
        assertNull(stack.popForRemoval())
    }

    @Test
    fun `FR-15 the stack holds 50 steps and drops the oldest past the cap`() {
        val stack = UndoStack()
        (1..UndoStack.LIMIT + 1).forEach { stack.push(entry(it)) }
        // Step 1 fell off the cap; popping walks 51 down to 2, newest first.
        var expected = UndoStack.LIMIT + 1
        var popped = 0
        while (true) {
            val step = stack.popForRemoval() ?: break
            assertEquals(expected, step.index)
            stack.removalSettled()
            popped++
            expected--
        }
        assertEquals(UndoStack.LIMIT, popped)
        assertFalse(stack.isNotEmpty())
    }

    @Test
    fun `FR-15 a second pop is ignored while a removal is in flight`() {
        val stack = UndoStack()
        stack.push(entry(1))
        stack.push(entry(2))
        // Single-flight: the next tap pops nothing, and the entry stays off the stack.
        assertNotNull(stack.popForRemoval())
        assertNull(stack.popForRemoval())
        stack.removalSettled()
        // Settled: the step below is now reachable.
        assertEquals(1, stack.popForRemoval()?.index)
    }

    @Test
    fun `FR-15 a failed removal restores the step on top and releases the flight`() {
        val stack = UndoStack()
        stack.push(entry(1))
        val popped = assertNotNull(stack.popForRemoval())
        // A push while the removal is in flight lands above the popped entry;
        // the restore re-adds on top of it, exactly as the ViewModel relied on.
        stack.push(entry(2))
        stack.restore(popped)
        assertEquals(1, stack.popForRemoval()?.index)
    }

    @Test
    fun `FR-15 clear releases the flight so the next session starts unblocked`() {
        val stack = UndoStack()
        stack.push(entry(1))
        assertNotNull(stack.popForRemoval())
        stack.clear()
        stack.push(entry(2))
        assertEquals(2, stack.popForRemoval()?.index)
    }
}
