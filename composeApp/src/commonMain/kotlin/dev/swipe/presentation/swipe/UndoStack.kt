package dev.swipe.presentation.swipe

import dev.swipe.domain.model.Decision

/**
 * One pushed step of the FR-15 undo stack: what to remove and where to return.
 *
 * FR-38 extends a step to a **hide**: [verdict] is null when the step hides rather
 * than decides, and undoing it unhides [id] instead of removing a decision row.
 * [pendingFavorite] carries an FR-22 favorite confirm the hide cleared, so undo can
 * restore it together with the returned card.
 */
data class UndoEntry(
    val id: String,
    val verdict: Decision? = null,
    val index: Int,
    val pendingFavorite: String? = null,
)

/**
 * The FR-15 undo stack: successful decisions in order, newest last, capped at
 * [LIMIT] (oldest dropped). The stack also owns the single-flight removal —
 * [popForRemoval] hands out at most one step at a time, so the popped entry
 * stays on top of every later push and a failed removal can restore stack
 * order with a plain [restore] re-add.
 *
 * The single-flight here only covers Undo taps. The mirror guard — queueing
 * Undo while a decision *write* is in flight (WP-9.10) — belongs to the caller
 * driving the writes, so it lives in `SwipeViewModel`.
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
