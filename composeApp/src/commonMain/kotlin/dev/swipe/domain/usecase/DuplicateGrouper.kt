package dev.swipe.domain.usecase

import dev.swipe.domain.model.DuplicateKey
import kotlin.math.abs

/**
 * Groups the Likely Duplicates candidates (FR-6, §6.5) into deck order: items taken
 * within 2 s of each other with identical pixel dimensions sit next to each other.
 *
 * Input is the date-sorted list from [dev.swipe.data.MediaLibrary.duplicateCandidates];
 * the grouper preserves that order and never sorts. A run grows while each candidate
 * matches its immediate predecessor — same width and height and within
 * [WINDOW_DUPLICATE_MS] of it — so a burst of shots spanning more than 2 s in
 * total still forms one group (chaining). Only runs of at least two members are
 * emitted; singletons and candidates without a creation date drop out.
 */
object DuplicateGrouper {
    /** FR-6: likely duplicates are taken within 2 s of their predecessor. */
    private const val WINDOW_DUPLICATE_MS = 2_000L

    /** Ids of every candidate in a group of >= 2, in input order, members adjacent (FR-6). */
    fun group(candidates: List<DuplicateKey>): List<String> {
        val result = mutableListOf<String>()
        var run = mutableListOf<DuplicateKey>()

        fun flush() {
            if (run.size >= 2) {
                result += run.map { it.id }
            }
            run = mutableListOf()
        }
        for (candidate in candidates) {
            if (candidate.createdAt == null) continue
            val previous = run.lastOrNull()
            if (previous != null && !joins(previous, candidate)) {
                flush()
            }
            run += candidate
        }
        flush()
        return result
    }

    /** Same pixel dimensions and within the window of each other (FR-6). */
    private fun joins(
        previous: DuplicateKey,
        next: DuplicateKey,
    ): Boolean {
        // Both dates are non-null: [group] admits only dated candidates into a run.
        val gapMs = previous.createdAt!!.minus(next.createdAt!!).inWholeMilliseconds
        return previous.widthPx == next.widthPx &&
            previous.heightPx == next.heightPx &&
            abs(gapMs) <= WINDOW_DUPLICATE_MS
    }
}
