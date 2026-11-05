package dev.swipe.presentation.settings

import kotlin.time.Instant

/**
 * The one state of [SettingsViewModel] (FR-29–31): the three setting values
 * mirrored live from [dev.swipe.data.SettingsStore], the version/build and
 * signing-expiry text, and the FR-30 reset confirm-dialog flag.
 */
data class SettingsUiState(
    /** Haptic feedback on (FR-29). Mirrors [dev.swipe.data.SettingsStore.haptics]. */
    val hapticsEnabled: Boolean = true,
    /** iCloud-only originals allowed to download (FR-29). Mirrors `includeCloudOnly`. */
    val includeCloudOnly: Boolean = true,
    /** Videos autoplay on top of the deck (FR-29). Mirrors `autoplayVideos`. */
    val autoplayVideos: Boolean = true,
    /** Version and build for the info row, from [dev.swipe.data.SystemActions.appVersion] (FR-31). */
    val appVersion: String = "",
    /**
     * Free-provisioning expiry for the signing row (FR-31), straight from
     * [dev.swipe.data.SystemActions.signingExpiry]; null — TestFlight, App Store,
     * or an unreadable profile — hides the row.
     */
    val signingExpiry: Instant? = null,
    /**
     * The "Reset all decisions?" confirm dialog is up (FR-30). Set only by
     * [SettingsIntent.RequestReset]; the clear runs only on [SettingsIntent.ConfirmReset].
     */
    val confirmResetVisible: Boolean = false,
)
