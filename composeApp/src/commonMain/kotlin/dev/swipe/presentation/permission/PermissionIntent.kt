package dev.swipe.presentation.permission

/**
 * What [PermissionViewModel] reacts to (AGENTS.md ViewModel convention). The
 * `readWrite` prompt itself is not an intent: it fires automatically once on
 * launch while access is `NOT_DETERMINED` (FR-1).
 */
sealed interface PermissionIntent {
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
