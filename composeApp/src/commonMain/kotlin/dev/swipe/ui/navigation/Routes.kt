package dev.swipe.ui.navigation

import dev.swipe.domain.model.MediaTypeFilter
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for the §5.8 screen map, used with the
 * `navigation-compose` serializable-route API (REQUIREMENTS §6.2). Only the
 * permission-flow destinations ([Onboarding], [Denied], [Home]) have screens
 * registered so far (WP-1.7); the rest are declared here and get wired up by
 * the work packages that own their screens (WP-1.9, WP-2.2, WP-3.3, WP-5.3,
 * WP-6.1).
 */

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
 * [typeFilter] is the Home media-type toggle applied on top of the source (FR-5).
 */
@Serializable
data class Swipe(
    val sourceJson: String,
    val typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
)

/** Session summary when the deck runs out (FR-21, WP-2.2). */
@Serializable
data object Summary

/** To-Delete review queue (FR-23, WP-3.3). */
@Serializable
data object Queue

/** Success screen after the iOS delete dialog confirms (FR-26, WP-3.3). */
@Serializable
data object DeleteSuccess

/** Settings (FR-29–31, WP-6.1). */
@Serializable
data object Settings
