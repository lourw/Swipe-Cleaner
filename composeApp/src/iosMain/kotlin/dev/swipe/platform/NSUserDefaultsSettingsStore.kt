package dev.swipe.platform

import dev.swipe.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSUserDefaults

/**
 * [SettingsStore] over `NSUserDefaults` (WP-1.4; FR-29, §6.2). The v1 defaults —
 * haptics on, iCloud-only items included, videos autoplay, onboarding unseen — are
 * registered rather than hard-coded at read sites, so a fresh install reads them
 * without any migration. Values persist across launches.
 */
class NSUserDefaultsSettingsStore : SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    // MUST stay above the StateFlow initializers below: registerDefaults has to run
    // before the first boolForKey read or the §6.4 defaults are silently dropped.
    init {
        defaults.registerDefaults(
            mapOf(
                KEY_HAPTICS to true,
                KEY_INCLUDE_CLOUD_ONLY to true,
                KEY_AUTOPLAY_VIDEOS to true,
                KEY_ONBOARDING_SEEN to false,
            ),
        )
    }

    private val _haptics = MutableStateFlow(defaults.boolForKey(KEY_HAPTICS))
    private val _includeCloudOnly = MutableStateFlow(defaults.boolForKey(KEY_INCLUDE_CLOUD_ONLY))
    private val _autoplayVideos = MutableStateFlow(defaults.boolForKey(KEY_AUTOPLAY_VIDEOS))
    private val _onboardingSeen = MutableStateFlow(defaults.boolForKey(KEY_ONBOARDING_SEEN))

    override val haptics: StateFlow<Boolean> = _haptics
    override val includeCloudOnly: StateFlow<Boolean> = _includeCloudOnly
    override val autoplayVideos: StateFlow<Boolean> = _autoplayVideos
    override val onboardingSeen: StateFlow<Boolean> = _onboardingSeen

    override fun setHaptics(enabled: Boolean) = write(KEY_HAPTICS, enabled, _haptics)

    override fun setIncludeCloudOnly(enabled: Boolean) = write(KEY_INCLUDE_CLOUD_ONLY, enabled, _includeCloudOnly)

    override fun setAutoplayVideos(enabled: Boolean) = write(KEY_AUTOPLAY_VIDEOS, enabled, _autoplayVideos)

    override fun setOnboardingSeen(seen: Boolean) = write(KEY_ONBOARDING_SEEN, seen, _onboardingSeen)

    /** Persists first, then publishes, so a read after a write never sees stale state. */
    private fun write(
        key: String,
        value: Boolean,
        flow: MutableStateFlow<Boolean>,
    ) {
        defaults.setBool(value, key)
        flow.value = value
    }

    private companion object {
        const val KEY_HAPTICS = "settings.haptics"
        const val KEY_INCLUDE_CLOUD_ONLY = "settings.includeCloudOnly"
        const val KEY_AUTOPLAY_VIDEOS = "settings.autoplayVideos"
        const val KEY_ONBOARDING_SEEN = "settings.onboardingSeen"
    }
}
