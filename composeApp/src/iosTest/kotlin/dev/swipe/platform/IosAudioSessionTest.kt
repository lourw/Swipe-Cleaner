@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FR-16 regression: the audio-session door drives the shared `AVAudioSession`.
 * It starts ambient (mixable, silent-friendly), a playback request switches the
 * category to `playback` so an unmuted video is audible past the ring/silent
 * switch, and releasing returns it to ambient so muted autoplay never stops
 * other apps' music. Only categories are asserted: activation itself can fail
 * on the simulator, which is irrelevant here.
 */
class IosAudioSessionTest {
    @Test
    fun `FR-16 audio session requests playback then returns to ambient`() {
        val audioSession = IosAudioSession()
        val shared = AVAudioSession.sharedInstance()
        assertEquals(AVAudioSessionCategoryAmbient, shared.category)
        audioSession.requestPlayback()
        assertEquals(AVAudioSessionCategoryPlayback, shared.category)
        audioSession.releasePlayback()
        assertEquals(AVAudioSessionCategoryAmbient, shared.category)
    }
}
