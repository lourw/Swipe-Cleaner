package dev.swipe.data

import kotlinx.coroutines.flow.StateFlow

/**
 * The app's settings (FR-29), implemented over `NSUserDefaults` (§6.2). Defaults:
 * haptics on, iCloud-only items included, videos autoplay; onboarding unseen.
 */
interface SettingsStore {
    /** Haptic feedback on or off (FR-29). Default `true`. */
    val haptics: StateFlow<Boolean>

    /** Allow network downloads for iCloud-only items (FR-29). Default `true`. */
    val includeCloudOnly: StateFlow<Boolean>

    /** Videos autoplay when their card is on top (FR-29). Default `true`. */
    val autoplayVideos: StateFlow<Boolean>

    /** Whether onboarding has been seen, so a returning user skips it (FR-1). Default `false`. */
    val onboardingSeen: StateFlow<Boolean>

    /** Update [haptics] (FR-29). */
    fun setHaptics(enabled: Boolean)

    /** Update [includeCloudOnly] (FR-29). */
    fun setIncludeCloudOnly(enabled: Boolean)

    /** Update [autoplayVideos] (FR-29). */
    fun setAutoplayVideos(enabled: Boolean)

    /** Record that onboarding was seen (FR-1). */
    fun setOnboardingSeen(seen: Boolean)
}
