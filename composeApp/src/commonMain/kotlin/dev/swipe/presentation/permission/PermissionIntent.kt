package dev.swipe.presentation.permission

/**
 * What [PermissionViewModel] reacts to (AGENTS.md ViewModel convention).
 */
sealed interface PermissionIntent {
    /** The onboarding screen's "Allow access" button (FR-1). */
    data object RequestAccess : PermissionIntent

    /** The denied screen's "Open Settings" button (FR-3). */
    data object OpenSettings : PermissionIntent

    /** The limited banner's "Manage" action, which opens the limited-library picker (FR-2). */
    data object ManageLimitedAccess : PermissionIntent

    /**
     * The app came to the foreground: re-read authorization and the FR-2 count.
     * Covers grants and revocations made in Settings; since WP-3.2 in-app
     * limited-library edits also refresh through `MediaLibrary.changes()`.
     */
    data object Recheck : PermissionIntent
}
