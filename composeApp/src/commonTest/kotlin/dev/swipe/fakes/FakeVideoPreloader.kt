package dev.swipe.fakes

import dev.swipe.data.VideoPreloader

/**
 * In-memory [VideoPreloader] for tests (§8.2, WP-4.1). Records every call in order;
 * the interface is fire-and-forget (nothing at the call site fails), so there is no
 * failure switch — playback failures surface as a missing item, not a thrown call.
 */
class FakeVideoPreloader : VideoPreloader {
    /** Every [preload] id, oldest first. */
    val preloadCalls = mutableListOf<String>()

    /** Every [cancel] id, oldest first. */
    val cancelCalls = mutableListOf<String>()

    override fun preload(id: String) {
        preloadCalls += id
    }

    override fun cancel(id: String) {
        cancelCalls += id
    }
}
