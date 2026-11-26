package dev.swipe.data

/**
 * The platform's audio session behind one door (FR-16). iOS picks the
 * `soloAmbient` category by default: it is silenced by the ring/silent switch
 * and non-mixable, which is the FR-16 bug — nothing in the app ever touched
 * `AVAudioSession`. The iOS implementation (`platform/IosAudioSession.kt`)
 * sits on the mixable, silent-friendly `ambient` category from launch, steps
 * up to `playback` when the user unmutes the video on screen, and returns.
 *
 * Muted playback must never call [requestPlayback]: silent autoplay has to
 * keep interrupting nothing, so it stays on the ambient category the whole
 * time it runs.
 */
interface AudioSession {
    /**
     * FR-16: sound was turned on for a video on screen. Switch to audible
     * playback that ignores the silent switch and interrupts other apps' audio.
     */
    fun requestPlayback()

    /** FR-16: sound was turned off again (or the deck closed). Return to mixable, silent-friendly audio; best effort. */
    fun releasePlayback()
}
