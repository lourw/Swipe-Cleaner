package dev.swipe.presentation.swipe

import dev.swipe.domain.model.Decision

/** One pushed step of the FR-15 undo stack: what to remove and where to return. */
data class UndoEntry(
    val id: String,
    val verdict: Decision,
    val index: Int,
)

/**
 * The FR-15 undo stack: successful decisions in order, newest last, capped at
 * [LIMIT] (oldest dropped). The stack also owns the single-flight removal —
 * [popForRemoval] hands out at most one step at a time, so the popped entry
 * stays on top of every later push and a failed removal can restore stack
 * order with a plain [restore] re-add.
 *
 * The single-flight here only covers Undo taps. The mirror guard — ignoring
 * Undo while a decision *write* is in flight — belongs to the caller driving
 * the writes, so it lives in `SwipeViewModel`.
 */
class UndoStack {
    private val entries = ArrayDeque<UndoEntry>()

    /** The popped step whose removal is in flight; further pops return null. */
    private var inFlight: UndoEntry? = null

    /** §6.6 canUndo: whether there is a step to return through. */
    fun isNotEmpty(): Boolean = entries.isNotEmpty()

    /** Record a durable decision as undoable; the oldest step falls off past the cap. */
    fun push(entry: UndoEntry) {
        if (entries.size == LIMIT) entries.removeFirst()
        entries.addLast(entry)
    }

    /**
     * Pop the newest step to remove, or null while a removal is already in
     * flight or the stack is empty (FR-15 single-flight, no-op on empty).
     */
    fun popForRemoval(): UndoEntry? {
        if (inFlight != null) return null
        val entry = entries.removeLastOrNull()
        if (entry != null) inFlight = entry
        return entry
    }

    /** The in-flight removal succeeded; nothing goes back on the stack. */
    fun removalSettled() {
        inFlight = null
    }

    /**
     * The in-flight removal failed: put the step back and release the flight.
     * Single-flight guarantees nothing was popped in between, so the re-add
     * restores the exact order.
     */
    fun restore(entry: UndoEntry) {
        inFlight = null
        entries.addLast(entry)
    }

    /** The review run is over (FR-21): the stack empties with it. */
    fun clear() {
        entries.clear()
        inFlight = null
    }

    companion object {
        /** FR-15: the undo stack holds up to 50 steps per session. */
        const val LIMIT = 50
    }
}
