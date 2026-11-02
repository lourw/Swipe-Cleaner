package dev.swipe.ui.components.swipe

/**
 * The three swipe directions of the card gesture (§5.6). Deliberately its own
 * type so the component stays decoupled from the domain `Verdict` enum
 * (WP-2.1 task 10); the session screen maps these to `Verdict` when wiring
 * the callback (WP-2.6).
 */
enum class SwipeVerdict {
    KEEP,
    DELETE,
    SKIP,
}
