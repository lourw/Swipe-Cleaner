@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class, // NSArray.create (FR-25)
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.swipe.platform

import dev.swipe.data.LONG_VIDEO_MIN_MS
import dev.swipe.data.MediaLibrary
import dev.swipe.data.monthBuckets
import dev.swipe.data.monthRange
import dev.swipe.data.photoKitDeleteResult
import dev.swipe.data.photoKitIsScreenshot
import dev.swipe.data.photoKitLibraryAccess
import dev.swipe.data.photoKitMediaType
import dev.swipe.domain.model.AlbumSource
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DeleteResult
import dev.swipe.domain.model.DuplicateKey
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.usecase.DuplicateGrouper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toNSDate
import platform.Foundation.NSArray
import platform.Foundation.NSCompoundPredicate
import platform.Foundation.NSPredicate
import platform.Foundation.NSSortDescriptor
import platform.Foundation.create
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAlbumRegular
import platform.Photos.PHAssetCollectionSubtypeAny
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumBursts
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumFavorites
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumLivePhotos
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumRecentlyAdded
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumScreenshots
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumSelfPortraits
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumVideos
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetCollectionTypeSmartAlbum
import platform.Photos.PHAssetMediaSubtypePhotoScreenshot
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHChange
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotoLibraryChangeObserverProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.math.roundToLong
import kotlin.time.Instant

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
 * The write side ([delete], WP-3.2) and the change observer ([changes]) also live
 * here: AGENTS.md rule 1 and the §8.3 check-rules guard keep this file the only
 * place that can ever call `performChanges` / `deleteAssets`.
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

    /**
     * **Albums** rows (FR-4, FR-8): user albums first, then the main smart albums —
     * Favorites, Recently Added, Videos, Selfies, Screenshots, Live Photos, Bursts.
     * ("Recents" is not a PhotoKit smart-album subtype in the iOS 26.5 SDK;
     * `SmartAlbumRecentlyAdded` is the closest and what the Photos app surfaces.)
     * Count is `estimatedAssetCount`, falling back to a lazy fetch count when PhotoKit
     * reports it unknown (`NSIntegerMax`), and the cover is the first object of a
     * creation-date-descending collection fetch — neither enumerates per asset (NFR-4),
     * so this is safe at Home startup.
     */
    override suspend fun albums(): List<AlbumSource> =
        withContext(fetchDispatcher) {
            val wantedSmartAlbums =
                setOf(
                    PHAssetCollectionSubtypeSmartAlbumFavorites,
                    PHAssetCollectionSubtypeSmartAlbumRecentlyAdded,
                    PHAssetCollectionSubtypeSmartAlbumVideos,
                    PHAssetCollectionSubtypeSmartAlbumSelfPortraits,
                    PHAssetCollectionSubtypeSmartAlbumScreenshots,
                    PHAssetCollectionSubtypeSmartAlbumLivePhotos,
                    PHAssetCollectionSubtypeSmartAlbumBursts,
                )
            val user =
                PHAssetCollection.fetchAssetCollectionsWithType(
                    PHAssetCollectionTypeAlbum,
                    PHAssetCollectionSubtypeAlbumRegular,
                    null,
                )
            val smart =
                PHAssetCollection.fetchAssetCollectionsWithType(
                    PHAssetCollectionTypeSmartAlbum,
                    PHAssetCollectionSubtypeAny,
                    null,
                )
            val result = mutableListOf<AlbumSource>()
            user.enumerateObjectsUsingBlock { collection, _, _ ->
                (collection as? PHAssetCollection)?.let { result += toAlbumSource(it) }
            }
            smart.enumerateObjectsUsingBlock { collection, _, _ ->
                val assetCollection = collection as? PHAssetCollection ?: return@enumerateObjectsUsingBlock
                if (assetCollection.assetCollectionSubtype in wantedSmartAlbums) {
                    result += toAlbumSource(assetCollection)
                }
            }
            result
        }

    /**
     * **By month** buckets (FR-5, FR-7). Enumerates **only** `creationDate` off the fetch
     * result and buckets in Kotlin via [monthBuckets], on [fetchDispatcher] — and the
     * caller must invoke this lazily when the By-month screen opens, never at Home
     * startup, because the enumeration is over the whole (type-filtered) library (NFR-4).
     * Buckets use the device's local zone: the same zone a card shows as its date (§5.7),
     * so a bucket and the [DeckSource.Month] deck opened from it agree on membership
     * ([monthRange] feeds the deck's predicate).
     */
    override suspend fun months(type: MediaTypeFilter): List<MonthBucket> =
        withContext(fetchDispatcher) {
            val dates = mutableListOf<Instant?>()
            assetsWithOptions(typePredicate(type)).enumerateObjectsUsingBlock { asset, _, _ ->
                (asset as? PHAsset)?.let { dates += it.creationDate?.toKotlinInstant() }
            }
            monthBuckets(dates, TimeZone.currentSystemDefault())
        }

    /**
     * Cheap per-filter counts for Home's **Smart filters** rows (FR-5, FR-6), safe at
     * startup (NFR-4): Screenshots and Long videos are lazy fetch counts over predicate
     * options, Selfies counts inside the `smartAlbumSelfPortraits` collection, Bursts
     * counts [burstAssetIds] (PhotoKit offers no predicate count for `burstIdentifier`,
     * see its KDoc), and Likely duplicates reuses the [DuplicateGrouper] run over the
     * id+date+size candidate scan — no metadata or image loading anywhere (FR-6).
     */
    override suspend fun smartCounts(type: MediaTypeFilter): Map<SmartFilter, Int> =
        withContext(fetchDispatcher) {
            val selfies = selfiesCollection()
            mapOf(
                SmartFilter.SCREENSHOTS to assetsWithOptions(screenshotPredicate(type)).count.toInt(),
                SmartFilter.LONG_VIDEOS to assetsWithOptions(longVideoPredicate(type)).count.toInt(),
                SmartFilter.BURSTS to burstAssetIds(type).size,
                SmartFilter.SELFIES to
                    if (selfies == null) {
                        0
                    } else {
                        PHAsset.fetchAssetsInAssetCollection(selfies, optionsWithPredicate(typePredicate(type))).count.toInt()
                    },
                SmartFilter.LIKELY_DUPLICATES to DuplicateGrouper.group(duplicateCandidates(type)).size,
            )
        }

    /**
     * Ids of [source] narrowed by [type] (FR-5) — the type predicate ANDed into **every**
     * source predicate — enumerating **only** `localIdentifier` off the fetch result, the
     * whole point of NFR-4. Deck order (§6.5): `Smart(LONG_VIDEOS)` comes back
     * duration-descending via a sort descriptor, every other source creation-date
     * descending (an explicit descriptor on album fetches, whose default is the album's
     * custom order). Sources: albums by collection ([DeckSource.Album.id] is the
     * collection `localIdentifier`), months by half-open `creationDate` range from
     * [monthRange] in the device zone — the same zone [months] bucketed, so deck and
     * bucket agree on membership (FR-7) — and the smart filters per
     * [smartAssetIds]. An unknown album id, a missing selfies album, or an empty month
     * yields an empty list.
     */
    override suspend fun assetIds(
        source: DeckSource,
        type: MediaTypeFilter,
    ): List<String> =
        withContext(fetchDispatcher) {
            when (source) {
                DeckSource.All -> {
                    idsFrom(assetsWithOptions(typePredicate(type)))
                }

                is DeckSource.Album -> {
                    albumAssetIds(source.id, type)
                }

                is DeckSource.Month -> {
                    monthAssetIds(source.year, source.month, type)
                }

                is DeckSource.Smart -> {
                    smartAssetIds(source.kind, type)
                }
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
     * One confirmed batch, one change request (FR-25): fetch the assets by local
     * identifier, then run the PhotoKit change request behind the iOS system dialog
     * with `performChanges { deleteAssets }`, bridged by [suspendCancellableCoroutine]
     * — the completion handler answers on a PhotoKit queue. The `NSError` it carries
     * decides the outcome through [photoKitDeleteResult]: success → `Deleted`,
     * `PHPhotosErrorUserCancelled` (3072) → `Cancelled` (FR-27, silent), anything else
     * → `Failed(localizedDescription)`.
     *
     * **Zero fetched assets answer [DeleteResult.Deleted] without a change request** —
     * no iOS dialog for a batch with nothing to delete (§6.8). That is also the
     * convergence path for a retried commit whose PhotoKit delete succeeded but whose
     * `markDeleted` store write failed: the second run finds nothing left to delete,
     * stays dialog-free, and lets `CommitDeletionsUseCase` mark the rows DELETED.
     */
    override suspend fun delete(ids: List<String>): DeleteResult =
        withContext(fetchDispatcher) {
            val assets = mutableListOf<PHAsset>()
            PHAsset.fetchAssetsWithLocalIdentifiers(ids, null).enumerateObjectsUsingBlock { asset, _, _ ->
                (asset as? PHAsset)?.let(assets::add)
            }
            if (assets.isEmpty()) return@withContext DeleteResult.Deleted
            val assetsToRemove = NSArray.create(array = assets)
            suspendCancellableCoroutine<DeleteResult> { continuation ->
                PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                    changeBlock = {
                        PHAssetChangeRequest.deleteAssets(assetsToRemove)
                    },
                    completionHandler = { success, error ->
                        continuation.resume(
                            photoKitDeleteResult(
                                success = success,
                                errorDomain = error?.domain,
                                errorCode = error?.code,
                                errorMessage = error?.localizedDescription,
                            ),
                        )
                    },
                )
            }
        }

    /**
     * The library change observer (FR-28, §6.8): a cold Flow that registers a
     * `PHPhotoLibraryChangeObserver` on every collect and unregisters it in
     * `awaitClose`, so cancelling the collector detaches the observer (NFR-7).
     * PhotoKit delivers `photoLibraryDidChange` on a queue of its own; every
     * delivery emits one `Unit` and the Flow is conflated, so a busy library
     * can never pile up work behind a slow consumer — each consumer re-reads
     * what it needs (the queue prune, the limited count). `trySend` drops a
     * tick only when the channel buffer overflows a wedged consumer; the drop
     * is harmless because consumers are re-read style and the next tick (or
     * their next load) converges anyway.
     */
    override fun changes(): Flow<Unit> =
        callbackFlow {
            val photoLibrary = PHPhotoLibrary.sharedPhotoLibrary()
            val observer = LibraryChangeObserver { trySend(Unit) }
            photoLibrary.registerChangeObserver(observer)
            awaitClose { photoLibrary.unregisterChangeObserver(observer) }
        }.conflate()

    /**
     * Cheap candidates for the Likely Duplicates filter (FR-6, §6.5): the
     * [duplicateCandidateKeys] scan (id + creationDate + pixel dimensions only) on
     * [fetchDispatcher], date-sorted for [DuplicateGrouper].
     */
    override suspend fun duplicateCandidates(type: MediaTypeFilter): List<DuplicateKey> =
        withContext(fetchDispatcher) { duplicateCandidateKeys(type) }

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

/**
 * Kotlin/Native `NSObject` conforming to `PHPhotoLibraryChangeObserver` (§6.8):
 * PhotoKit calls `photoLibraryDidChange` on its own queue whenever the library
 * changes; every call forwards one tick to the [changes] Flow.
 */
private class LibraryChangeObserver(
    private val onChange: () -> Unit,
) : NSObject(),
    PHPhotoLibraryChangeObserverProtocol {
    override fun photoLibraryDidChange(changeInstance: PHChange) {
        onChange()
    }
}
