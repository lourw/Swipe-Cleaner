package dev.swipe.ui.screens.swipe

import dev.swipe.data.PerfSpan
import dev.swipe.data.PerfTrace

/**
 * The NFR-1 span tracker for one deck screen (WP-6.3): opens both next-card
 * spans at a commit and closes them as the new top card's images arrive.
 *
 * [onCommit] runs on every commit path (gesture and ActionBar button share
 * the same lambda in `SwipeScreen`) *before* the intent, so the span covers
 * persist-then-advance plus the image load. [onImage] runs on each top-card
 * image emission: the first one closes [PerfSpan.NEXT_CARD_DEGRADED] (a
 * full-quality image that beats the degraded one satisfies NFR-1 too —
 * the cache may serve it first), and the first non-degraded one closes
 * [PerfSpan.NEXT_CARD_FULL]. Emissions from the committed card itself — its
 * own late full-quality load landing after the flight, or an undo that
 * remounts it — are ignored by id: only a *different* card's images may
 * close the spans. Emissions outside a pending commit are no-ops; a commit
 * that never sees an image leaves the span open, which Instruments renders
 * as an open-ended interval to be ignored (docs/perf.md).
 */
internal class CommitLatencyTrace(
    private val trace: PerfTrace,
) {
    private var awaitingDegraded = false
    private var awaitingFull = false
    private var committedId: String? = null

    fun onCommit(committedId: String?) {
        this.committedId = committedId
        awaitingDegraded = true
        awaitingFull = true
        trace.begin(PerfSpan.NEXT_CARD_DEGRADED)
        trace.begin(PerfSpan.NEXT_CARD_FULL)
    }

    fun onImage(
        cardId: String?,
        isDegraded: Boolean,
    ) {
        if (cardId != null && cardId == committedId) return
        if (awaitingDegraded) {
            awaitingDegraded = false
            trace.end(PerfSpan.NEXT_CARD_DEGRADED)
        }
        if (awaitingFull && !isDegraded) {
            awaitingFull = false
            trace.end(PerfSpan.NEXT_CARD_FULL)
        }
    }
}
