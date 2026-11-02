package dev.swipe.domain.model

/**
 * PhotoKit photo-library permission state (FR-1, FR-2, FR-3), mapped from
 * `PHAuthorizationStatus` in iosMain (§6.4).
 */
enum class LibraryAccess {
    /** Full read/write access (`authorized`). */
    FULL,

    /** Limited access: only the items the user picked (`limited`) (FR-2). */
    LIMITED,

    /** The user refused (`denied` or `restricted`); Settings is the only way back (FR-3). */
    DENIED,

    /** Not asked yet; the onboarding screen comes first (FR-1). */
    NOT_DETERMINED,
}
