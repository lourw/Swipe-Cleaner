// Type-safe navigation routes for the §5.8 screen map, used with the
// `navigation-compose` serializable-route API (REQUIREMENTS §6.2). Only the
// permission-flow destinations (Onboarding, Denied, Home) have screens
// registered so far (WP-1.7); the rest are declared here and get wired up by
// the work packages that own their screens (WP-1.9, WP-2.2, WP-3.3, WP-5.3,
// WP-6.1).

package dev.swipe.ui.navigation

import kotlinx.serialization.Serializable

/** First-launch explanation, then the `PHAccessLevel.readWrite` request (FR-1). */
@Serializable
data object Onboarding

/** Blocking screen when photo access is denied; offers Open Settings (FR-3). */
@Serializable
data object Denied

/** Home; placeholder until WP-5.3 builds the full screen (FR-4). */
@Serializable
data object Home

/** User albums and main smart albums with covers and counts (FR-8, WP-5.3). */
@Serializable
data object Albums

/** Year → month buckets with counts, newest first (FR-7, WP-5.3). */
@Serializable
data object Months

/** The v1 smart filters (FR-6, WP-5.3). */
@Serializable
data object SmartFilters

/**
 * The swipe deck (WP-1.9). [sourceJson] is the session's `DeckSource` serialized as JSON
 * (§7) — kept as a string so the route stays small and readable in logs.
 * [typeFilter] is the Home media-type toggle applied on top of the source (FR-5),
 * carried as a [dev.swipe.domain.model.MediaTypeFilter] *name*: the navigation
 * library cannot resolve a `NavType` for an enum argument on a type-safe route,
 * so destinations decode it defensively (unknown names fall back to `ALL`).
 */
@Serializable
data class Swipe(
    val sourceJson: String,
    val typeFilter: String = "ALL",
)

/**
 * Session summary when the deck runs out (FR-21, WP-2.3). The counts ride the
 * route so the summary survives process death and needs no ViewModel: they are
 * final the moment the deck ends.
 */
@Serializable
data class Summary(
    val kept: Int,
    val queued: Int,
    val skipped: Int,
)

/** To-Delete review queue (FR-23, WP-3.3). */
@Serializable
data object Queue

/** Success screen after the iOS delete dialog confirms (FR-26, WP-3.3). */
@Serializable
data object DeleteSuccess

/** Settings (FR-29–31, WP-6.1). */
@Serializable
data object Settings
