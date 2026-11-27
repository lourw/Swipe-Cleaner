package dev.swipe.presentation.permission

import dev.swipe.domain.model.LibraryAccess

/**
 * Screens of the permission flow (REQUIREMENTS §5.8 screen map):
 * Home, or Denied → Settings → Home (FR-1–3).
 */
enum class PermissionScreen {
    /** Access denied; only the Settings app gets the user out (FR-3). */
    DENIED,

    /**
     * Home — the app opens straight here on first launch, with the iOS access
     * prompt over the placeholder state (FR-1), and again for a returning user
     * with [LibraryAccess.FULL] or [LibraryAccess.LIMITED].
     */
    HOME,
}

/**
 * The one state of [PermissionViewModel] (AGENTS.md ViewModel convention).
 */
data class PermissionUiState(
    /**
     * The screen the permission flow shows; the NavHost's start destination
     * (WP-1.7). `null` while the first [dev.swipe.data.MediaLibrary.authorization]
     * read is in flight — the theme background is the splash.
     */
    val startScreen: PermissionScreen? = null,
    /** The most recent authorization outcome (FR-1–3). */
    val access: LibraryAccess? = null,
    /**
     * FR-2: how many items the limited library grants access to; shown by the Home
     * banner. Non-null only while [access] is [LibraryAccess.LIMITED].
     */
    val limitedCount: Int? = null,
    /**
     * §5.6 Reduce Motion, NFR-8: whether the system asks for reduced motion. The
     * ViewModel mirrors `SystemActions.isReduceMotionEnabled()` so the flag travels
     * down the NavHost (via `LocalReduceMotion`) instead of being injected into
     * composables (hard rule 5).
     */
    val reduceMotion: Boolean = false,
)
