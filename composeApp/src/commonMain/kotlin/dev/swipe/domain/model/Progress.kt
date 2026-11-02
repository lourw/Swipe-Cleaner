package dev.swipe.domain.model

/** Session progress shown as `reviewed / total` in the top bar (FR-19, §6.6). */
data class Progress(
    /** Items decided so far in this session. */
    val reviewed: Int,
    /** Total items in the built deck. */
    val total: Int,
)
