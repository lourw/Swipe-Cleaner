package dev.swipe.fakes

import dev.swipe.data.Haptics

/**
 * In-memory [Haptics] for tests (§8.2). Every fire is appended to [events] in order, so
 * tests can assert the §5.6 mapping (threshold → selection, keep/skip → light,
 * delete → medium, batch done → success). [failNext] throws once from the next fire.
 */
class FakeHaptics : Haptics {
    /** Which haptic fired. */
    enum class Event { SELECTION, IMPACT_LIGHT, IMPACT_MEDIUM, NOTIFICATION_SUCCESS }

    /** Events fired so far, oldest first. */
    val events = mutableListOf<Event>()

    /** When non-null, the next haptic call throws this once and the switch resets. */
    var failNext: Throwable? = null

    override fun selection() = fire(Event.SELECTION)

    override fun impactLight() = fire(Event.IMPACT_LIGHT)

    override fun impactMedium() = fire(Event.IMPACT_MEDIUM)

    override fun notificationSuccess() = fire(Event.NOTIFICATION_SUCCESS)

    private fun fire(event: Event) {
        failNext?.let {
            failNext = null
            throw it
        }
        events += event
    }
}
