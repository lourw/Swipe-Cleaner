@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.MediaLibrary
import dev.swipe.data.photoKitIsScreenshot
import dev.swipe.data.photoKitLibraryAccess
import dev.swipe.data.photoKitMediaType
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DeleteResult
import dev.swipe.domain.model.DuplicateKey
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.datetime.toKotlinInstant
import platform.Foundation.NSPredicate
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHFetchOptions
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/**
 * PhotoKit-backed [MediaLibrary] read side (WP-1.4; FR-1–3, §6.5 step 1, §6.8).
 *
 * Hot-path discipline (NFR-4): `assetIds` enumerates **only** `localIdentifier` off a
 * `PHFetchResult` — no other asset property is touched, so a 50k-item library stays
 * inside the 1.5 s startup budget — and `items` fetches metadata in pages of at most
 * 20 ids, in the order given (§6.5 step 2). Every fetch runs on [Dispatchers.Default]
 * (§6.8). No networking happens here; PhotoKit's own iCloud downloads are the only
 * network traffic (NFR-6).
 *
 * The write side is deliberately absent: `delete` answers
 * [DeleteResult.Failed] until WP-3.2 implements the change request, and AGENTS.md
 * rule 1 keeps this file the only place that could ever call `performChanges`.
 */
class PhotoKitMediaLibrary(
    private val fetchDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaLibrary {
    override suspend fun authorization(): LibraryAccess =
        withContext(fetchDispatcher) {
            photoKitLibraryAccess(PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite).toInt())
        }

    /**
     * Prompts with `PHAccessLevelReadWrite` (FR-1). The handler fires on a PhotoKit
     * queue with the outcome; `suspendCancellableCoroutine` resumes with it (or never,
     * if the caller is cancelled — the callback is harmless afterwards).
     */
    override suspend fun requestAuthorization(): LibraryAccess =
        suspendCancellableCoroutine { continuation ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { status ->
                continuation.resume(photoKitLibraryAccess(status.toInt()))
            }
        }

    /** Only [DeckSource.All] until WP-5.1 adds albums, months, and smart filters (FR-4). */
    override suspend fun sources(): List<DeckSource> = listOf(DeckSource.All)

    /**
     * Fetches every asset of [type] library-wide (FR-4, FR-5) and enumerates only
     * `localIdentifier` — the whole point of NFR-4. Live Photos are images to PhotoKit,
     * so the PHOTOS predicate includes them (FR-5). Creation-date descending is the
     * fetch default (§6.5); the shuffle happens in `BuildDeckUseCase`.
     */
    override suspend fun assetIds(
        source: DeckSource,
        type: MediaTypeFilter,
    ): List<String> {
        if (source != DeckSource.All) {
            // Album / Month / Smart sources arrive with WP-5.1; M1's deck only shuffles All.
            return emptyList()
        }
        return withContext(fetchDispatcher) {
            val options = PHFetchOptions()
            options.predicate =
                when (type) {
                    MediaTypeFilter.ALL -> null
                    MediaTypeFilter.PHOTOS -> NSPredicate.predicateWithFormat("mediaType == ${PHAssetMediaTypeImage.toInt()}")
                    MediaTypeFilter.VIDEOS -> NSPredicate.predicateWithFormat("mediaType == ${PHAssetMediaTypeVideo.toInt()}")
                }
            val result = PHAsset.fetchAssetsWithOptions(options)
            val ids = ArrayList<String>(result.count.toInt())
            result.enumerateObjectsUsingBlock { asset, _, _ ->
                (asset as? PHAsset)?.localIdentifier?.let(ids::add)
            }
            ids
        }
    }

    /**
     * Metadata for [ids] in pages of [METADATA_PAGE_SIZE] (§6.5 step 2), input order
     * preserved — `fetchAssetsWithLocalIdentifiers` returns assets in an arbitrary
     * order, so each page is re-sequenced against the ids it was asked for. Assets
     * PhotoKit no longer knows (deleted elsewhere) drop out (FR-28).
     */
    override suspend fun items(ids: List<String>): List<MediaItem> =
        withContext(fetchDispatcher) {
            ids.chunked(METADATA_PAGE_SIZE).flatMap { page ->
                val fetched = PHAsset.fetchAssetsWithLocalIdentifiers(page, null)
                val byId = HashMap<String, PHAsset>(page.size)
                fetched.enumerateObjectsUsingBlock { asset, _, _ ->
                    (asset as? PHAsset)?.let { byId[it.localIdentifier] = it }
                }
                page.mapNotNull { id -> byId[id]?.let(::toMediaItem) }
            }
        }

    /**
     * Not wired yet: WP-3.2 implements `performChanges { deleteAssets }` behind the
     * in-app confirmation (FR-25). Failing loudly is the safe answer — nothing here
     * can touch the library (NFR-5, AGENTS.md rule 1).
     */
    override suspend fun delete(ids: List<String>): DeleteResult = DeleteResult.Failed("not implemented")

    /** The change observer arrives with WP-3.2 (FR-28); quiet until then. */
    override fun changes(): Flow<Unit> = emptyFlow()

    /** The cheap candidate scan arrives with WP-5.1 (FR-6); empty until then. */
    override suspend fun duplicateCandidates(type: MediaTypeFilter): List<DuplicateKey> = emptyList()

    /**
     * `PHAsset` → [MediaItem] (§7). The non-trivial bit tests live in
     * [dev.swipe.data.photoKitMediaType] so they are unit-tested; `isCloudOnly` stays
     * null because PhotoKit only learns it once a load is attempted (§7, R6).
     */
    private fun toMediaItem(asset: PHAsset): MediaItem? {
        val type = photoKitMediaType(asset.mediaType.toInt(), asset.mediaSubtypes.toInt()) ?: return null
        return MediaItem(
            id = asset.localIdentifier,
            type = type,
            createdAt = asset.creationDate?.toKotlinInstant(),
            durationMs = if (type == MediaType.VIDEO) (asset.duration * MS_PER_SECOND).roundToLong() else null,
            widthPx = asset.pixelWidth.toInt(),
            heightPx = asset.pixelHeight.toInt(),
            isFavorite = asset.favorite,
            isScreenshot = photoKitIsScreenshot(asset.mediaSubtypes.toInt()),
            burstId = asset.burstIdentifier,
            isCloudOnly = null,
        )
    }

    private companion object {
        /** Metadata page size from §6.5 step 2 (and hard rule 6). */
        const val METADATA_PAGE_SIZE = 20

        /** NSTimeInterval (seconds) → milliseconds. */
        const val MS_PER_SECOND = 1000.0
    }
}
