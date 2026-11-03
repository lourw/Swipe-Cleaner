package dev.swipe.presentation.swipe

import dev.swipe.domain.model.Decision

/**
 * One-shot ViewModel → UI event (§6.6). These are transient commands, not state:
 * they ride a `Channel` exposed as [SwipeViewModel.events], so each one is delivered
 * to exactly one collector and never replays on recomposition — unlike state fields,
 * which a late collector would read as still-pending.
 */
sealed interface SwipeEvent {
    /**
     * The card comes back and the UI plays the return animation from [decision]'s
     * side (260 ms, §5.6). Sent when an undo succeeds (FR-15) and when a Delete on a
     * favorite is refused for confirmation (FR-22); [decision] is the verdict that
     * took the card out, which is also the way it returns.
     */
    data class ReturnCard(
        val decision: Decision,
    ) : SwipeEvent
}
