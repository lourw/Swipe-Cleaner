@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.AudioSession
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive

/**
 * `AVAudioSession` behind the [AudioSession] door (FR-16). The app launches on
 * the mixable `ambient` category — bound `createdAtStart` in Koin — so muted
 * autoplay never stops other apps' music, and the ring/silent switch still
 * works for it. [requestPlayback] switches to `playback` and activates it: no
 * mix options, so unmuting interrupts other audio, like Photos (decided
 * 2026-11-26). [releasePlayback] goes back to `ambient` and deactivates with
 * the notify-others option so interrupted apps resume.
 *
 * The Boolean results of every call are ignored: deactivating fails while a
 * muted player is still running, which is harmless (the category switch in
 * [requestPlayback] re-activates on the next unmute anyway).
 */
class IosAudioSession : AudioSession {
    private val session = AVAudioSession.sharedInstance()

    init {
        session.setCategory(AVAudioSessionCategoryAmbient, error = null)
    }

    override fun requestPlayback() {
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
    }

    override fun releasePlayback() {
        session.setCategory(AVAudioSessionCategoryAmbient, error = null)
        session.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
    }
}
