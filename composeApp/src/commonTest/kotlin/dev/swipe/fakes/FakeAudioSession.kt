package dev.swipe.fakes

import dev.swipe.data.AudioSession

/**
 * In-memory [AudioSession] for tests (§8.2). Every call appends `"request"` or
 * `"release"` to [calls] in order, so tests can assert that unmuting requests
 * a playback session and that muted playback never does (FR-16), and in how
 * many request/release pairs the deck actually ran.
 */
class FakeAudioSession : AudioSession {
    /** Session calls so far, oldest first: `"request"` and `"release"`. */
    val calls = mutableListOf<String>()

    override fun requestPlayback() {
        calls += "request"
    }

    override fun releasePlayback() {
        calls += "release"
    }
}
