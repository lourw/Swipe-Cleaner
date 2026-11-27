package dev.swipe.data

import kotlinx.coroutines.flow.StateFlow

/**
 * The app's settings (FR-29), implemented over `NSUserDefaults` (§6.2). Defaults:
 * haptics on, iCloud-only items included, videos autoplay, sensitive photos hidden,
 * video sound off.
 */
interface SettingsStore {
    /** Haptic feedback on or off (FR-29). Default `true`. */
    val haptics: StateFlow<Boolean>

    /** Allow network downloads for iCloud-only items (FR-29). Default `true`. */
    val includeCloudOnly: StateFlow<Boolean>

    /** Videos autoplay when their card is on top (FR-29). Default `true`. */
    val autoplayVideos: StateFlow<Boolean>

    /** Hide photos the OS flags as sensitive in the deck (FR-33). Default `true`. */
    val hideSensitive: StateFlow<Boolean>

    /**
     * Swipe sessions start with video sound on (FR-16, FR-29). Default `false`:
     * a new session is muted unless the user opted into sound.
     */
    val playVideoSound: StateFlow<Boolean>

    /** Update [haptics] (FR-29). */
    fun setHaptics(enabled: Boolean)

    /** Update [includeCloudOnly] (FR-29). */
    fun setIncludeCloudOnly(enabled: Boolean)

    /** Update [autoplayVideos] (FR-29). */
    fun setAutoplayVideos(enabled: Boolean)

    /** Update [hideSensitive] (FR-33). */
    fun setHideSensitive(enabled: Boolean)

    /** Update [playVideoSound] (FR-16, FR-29). */
    fun setPlayVideoSound(enabled: Boolean)
}
