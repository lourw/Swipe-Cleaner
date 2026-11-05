package dev.swipe.fakes

import dev.swipe.data.PerfSpan
import dev.swipe.data.PerfTrace

/**
 * In-memory [PerfTrace] for tests (WP-6.3): records every span open and
 * close in call order so tests can assert the commit path instruments the
 * NFR-1 intervals without an OS trace.
 */
class FakePerfTrace : PerfTrace {
    /** One [begin] or [end] call. */
    enum class Call {
        BEGIN,
        END,
    }

    /** Every call, oldest first. */
    val calls = mutableListOf<Pair<Call, PerfSpan>>()

    override fun begin(span: PerfSpan) {
        calls += Call.BEGIN to span
    }

    override fun end(span: PerfSpan) {
        calls += Call.END to span
    }
}
