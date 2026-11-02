package dev.swipe.fakes

import dev.swipe.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [SettingsStore] for tests (§8.2), with the production defaults: haptics,
 * iCloud-only inclusion and autoplay start on, onboarding unseen (FR-29, FR-1).
 * Every setter is recorded as `"name=value"` in [setCalls]; [failNextSet] throws once.
 */
class FakeSettingsStore : SettingsStore {
    override val haptics = MutableStateFlow(true)
    override val includeCloudOnly = MutableStateFlow(true)
    override val autoplayVideos = MutableStateFlow(true)
    override val onboardingSeen = MutableStateFlow(false)

    /** Every setter call as `"name=value"`, oldest first. */
    val setCalls = mutableListOf<String>()

    /** When non-null, the next setter throws this once and the switch resets. */
    var failNextSet: Throwable? = null

    override fun setHaptics(enabled: Boolean) = set("haptics", haptics, enabled)

    override fun setIncludeCloudOnly(enabled: Boolean) = set("includeCloudOnly", includeCloudOnly, enabled)

    override fun setAutoplayVideos(enabled: Boolean) = set("autoplayVideos", autoplayVideos, enabled)

    override fun setOnboardingSeen(seen: Boolean) = set("onboardingSeen", onboardingSeen, seen)

    private fun set(
        name: String,
        flow: MutableStateFlow<Boolean>,
        value: Boolean,
    ) {
        setCalls += "$name=$value"
        failNextSet?.let {
            failNextSet = null
            throw it
        }
        flow.value = value
    }
}
