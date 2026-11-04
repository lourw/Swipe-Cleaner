package dev.swipe.domain.model

/**
 * One selectable row for **Albums** (FR-4, FR-8): a user album or one of the main smart albums
 * (Favorites, Recents, Videos, Selfies, Screenshots, Live Photos, Bursts). [id] is the PhotoKit
 * collection `localIdentifier` — the same id a [DeckSource.Album] carries, so opening a row is
 * just `DeckSource.Album(id, title)`. [count] is a cheap estimate (PhotoKit
 * `estimatedAssetCount`, falling back to a fetch count) and never a per-asset enumeration
 * (NFR-4); [coverAssetId] drives the 40 dp row thumbnail through `MediaLoader.image` and is
 * null for an empty album.
 */
data class AlbumSource(
    /** Stable PhotoKit collection identifier. */
    val id: String,
    /** Title shown on the row (`PHAssetCollection.localizedTitle`). */
    val title: String,
    /** Item count in the album (cheap estimate, NFR-4). */
    val count: Int,
    /** Newest asset's id for the cover thumbnail; null when the album is empty. */
    val coverAssetId: String?,
)
