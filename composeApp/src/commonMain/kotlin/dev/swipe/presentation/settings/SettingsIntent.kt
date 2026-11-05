package dev.swipe.presentation.settings

/**
 * Committed user actions on the Settings screen (FR-29–31). Toggle switches
 * report their new value; the reset rides the two-step Request/Confirm pair so
 * the confirm dialog is the gate (FR-30).
 */
sealed interface SettingsIntent {
    /** Haptics toggle (FR-29): writes [enabled] to `SettingsStore.setHaptics`. */
    data class SetHaptics(
        val enabled: Boolean,
    ) : SettingsIntent

    /** Include-iCloud-only toggle (FR-29): writes `SettingsStore.setIncludeCloudOnly`. */
    data class SetIncludeCloudOnly(
        val enabled: Boolean,
    ) : SettingsIntent

    /** Autoplay-videos toggle (FR-29): writes `SettingsStore.setAutoplayVideos`. */
    data class SetAutoplayVideos(
        val enabled: Boolean,
    ) : SettingsIntent

    /**
     * "Reset all decisions" tap: raises the confirm dialog
     * ([SettingsUiState.confirmResetVisible]). **Nothing is cleared by this
     * intent** (FR-30).
     */
    data object RequestReset : SettingsIntent

    /**
     * The dialog's Reset button: the only intent that calls
     * `DecisionStore.clearKeeps()` (FR-30). Ignored unless the dialog is up.
     */
    data object ConfirmReset : SettingsIntent

    /** The dialog's Cancel: closes it; nothing is cleared (FR-30). */
    data object CancelReset : SettingsIntent
}
