package dev.swipe.fakes

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Deterministic clock for tests (§8.2 "injected Clock"). The app injects
 * `kotlin.time.Clock` directly — it is stable in the Kotlin 2.4 stdlib, so no custom
 * interface exists in `dev.swipe.data` — and this fake implements that standard interface.
 */
class FakeClock(
    start: Instant = Instant.fromEpochMilliseconds(0),
) : Clock {
    /** The instant [now] returns. */
    var currentTime: Instant = start

    /** How many times [now] has been called. */
    var nowCalls = 0
        private set

    override fun now(): Instant {
        nowCalls++
        return currentTime
    }

    /** Move the clock forward (or back, for a negative [duration]). */
    fun advanceBy(duration: Duration) {
        currentTime += duration
    }
}
