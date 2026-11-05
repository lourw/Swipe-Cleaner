package dev.swipe.data

/**
 * The named performance intervals WP-6.3 traces against NFR-1 and NFR-4
 * (procedure and results in `docs/perf.md`). Each value's [name] is the
 * span name in the OS trace — the iOS implementation emits `os_signpost`
 * intervals under the `dev.swipe.cleaner.perf` subsystem.
 */
enum class PerfSpan {
    /**
     * NFR-1 (< 100 ms): swipe commit → the next card's degraded image shown.
     * Ends on the new top card's *first* image emission; a full-quality
     * image that beats the degraded one satisfies the span too.
     */
    NEXT_CARD_DEGRADED,

    /** NFR-1 (< 500 ms for local assets): swipe commit → the next card's full-quality image shown. */
    NEXT_CARD_FULL,

    /**
     * NFR-4 (< 1.5 s at 50k items): cold start → Home interactive. Begins
     * at the Kotlin entry point (`MainViewController()`, a floor for the
     * real process spawn) and ends at Home's first composition, which
     * always carries the type toggle while counts may still stream in.
     */
    COLD_START_HOME,
}

/**
 * Opens and closes named OS-level performance intervals for Instruments
 * (WP-6.3, NFR-1, NFR-4). The iOS implementation is
 * `iosMain/platform/IosPerfTrace.kt`; [NoPerfTrace] serves tests and any
 * build that should not trace.
 *
 * Contract: [end] closes the span's most recent unmatched [begin]; an [end]
 * without a matching [begin] is dropped, so a span that never completes
 * (a cold start that lands on Onboarding, say) simply renders as an
 * open-ended interval. Both calls must be cheap — [begin] runs inside the
 * card-commit path, and the instrumentation must never regress the NFR-1
 * budget it measures.
 */
interface PerfTrace {
    fun begin(span: PerfSpan)

    fun end(span: PerfSpan)
}

/** [PerfTrace] that does nothing: the commonTest default and the fallback parameter. */
object NoPerfTrace : PerfTrace {
    override fun begin(span: PerfSpan) = Unit

    override fun end(span: PerfSpan) = Unit
}
