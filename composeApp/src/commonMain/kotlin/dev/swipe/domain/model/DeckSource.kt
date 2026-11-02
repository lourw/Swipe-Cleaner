package dev.swipe.domain.model

import kotlinx.serialization.Serializable

/**
 * Where a review session draws its items (FR-4), as in REQUIREMENTS §7.
 *
 * [Serializable] because the session row stores the source as JSON (`session.source`, §7)
 * and navigation routes carry it (§6.3).
 */
@Serializable
sealed interface DeckSource {
    /** “Shuffle everything” (FR-4). */
    @Serializable
    data object All : DeckSource

    /** A user album or one of the main smart albums (FR-4, FR-8). */
    @Serializable
    data class Album(
        /** Stable collection identifier (PhotoKit `localIdentifier`). */
        val id: String,
        /** Title shown on the Home row. */
        val title: String,
    ) : DeckSource

    /** Everything created in one calendar month, UTC (FR-4, FR-7). */
    @Serializable
    data class Month(
        /** Proleptic Gregorian year, e.g. 2026. */
        val year: Int,
        /** Month of year, 1..12. */
        val month: Int,
    ) : DeckSource

    /** One of the v1 smart filters (FR-4, FR-6). */
    @Serializable
    data class Smart(
        val kind: SmartFilter,
    ) : DeckSource
}
