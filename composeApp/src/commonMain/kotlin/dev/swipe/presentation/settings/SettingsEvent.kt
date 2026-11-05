package dev.swipe.presentation.settings

/**
 * One-shot ViewModel → UI events for Settings (§6.6 convention: a `Channel`,
 * so a late collector never replays a stale toast).
 */
sealed interface SettingsEvent {
    /** FR-30: the KEEP history was cleared; the screen toasts. */
    data object ResetDone : SettingsEvent

    /** FR-30: the clear failed; the KEEP history is unchanged and the user hears why. */
    data object ResetFailed : SettingsEvent

    /** FR-29: a settings write failed; the toggle falls back to the stored value. */
    data object SaveFailed : SettingsEvent
}
