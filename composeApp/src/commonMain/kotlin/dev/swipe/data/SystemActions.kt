package dev.swipe.data

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Things only the system can do: open Settings, present pickers, report accessibility
 * state and signing (REQUIREMENTS §6.4 additions; implemented in `iosMain/platform/`).
 */
interface SystemActions {
    /** Open this app's page in the Settings app (FR-3). */
    fun openSettings()

    /** Present the limited-library picker so the user can add items (FR-2). */
    fun presentLimitedLibraryPicker()

    /**
     * Whether Reduce Motion is on, re-emitted on change; the swipe card switches to
     * crossfades when it is (§5.6, NFR-8).
     */
    fun isReduceMotionEnabled(): Flow<Boolean>

    /** Version and build string for Settings (FR-31). */
    fun appVersion(): String

    /** When free provisioning expires, if it can be read; Settings hides the row otherwise (FR-31). */
    fun signingExpiry(): Instant?
}
