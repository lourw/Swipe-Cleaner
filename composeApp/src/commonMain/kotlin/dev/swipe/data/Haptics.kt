package dev.swipe.data

/**
 * Haptic feedback (REQUIREMENTS §5.6, NFR-8). Implemented with UIKit feedback generators
 * in `iosMain/platform/`; implementations must stay silent while `SettingsStore.haptics`
 * is off (FR-29).
 */
interface Haptics {
    /** Selection tick while the drag crosses a commit threshold (§5.6). */
    fun selection()

    /** Light impact when a Keep or Skip commits (§5.6). */
    fun impactLight()

    /** Medium impact when a Delete commits (§5.6). */
    fun impactMedium()

    /** Success notification after a confirmed batch delete (FR-26, §6.7). */
    fun notificationSuccess()
}
