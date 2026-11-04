@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class, // NSArray.create (FR-25)
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.swipe.platform

import dev.swipe.data.LONG_VIDEO_MIN_MS
import dev.swipe.data.MediaLibrary
import dev.swipe.data.monthBuckets
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
     * Cheap candidates for the Likely Duplicates filter (FR-6, §6.5): enumerates **only**
     * `creationDate`, `pixelWidth`, and `pixelHeight` off the fetch result — the three
     * fields [DuplicateKey] carries, nothing else (NFR-4) — on [fetchDispatcher], sorted
     * by creation date descending (nulls last), matching the fake and what
     * [DuplicateGrouper] expects (§6.4: date-sorted; §6.5: newest-first deck order).
     */
    override suspend fun duplicateCandidates(type: MediaTypeFilter): List<DuplicateKey> =
        withContext(fetchDispatcher) {
            val keys = mutableListOf<DuplicateKey>()
            assetsWithOptions(typePredicate(type)).enumerateObjectsUsingBlock { asset, _, _ ->
                (asset as? PHAsset)?.let {
                    keys +=
                        DuplicateKey(
                            id = it.localIdentifier,
                            createdAt = it.creationDate?.toKotlinInstant(),
                            widthPx = it.pixelWidth.toInt(),
                            heightPx = it.pixelHeight.toInt(),
                        )
                }
            }
            keys.sortWith { a, b ->
                when {
                    a.createdAt == null && b.createdAt == null -> 0
                    a.createdAt == null -> 1
                    b.createdAt == null -> -1
                    else -> b.createdAt.compareTo(a.createdAt)
                }
            }
            keys
        }

    /**
     * A fetch of every asset matching [predicate], library-wide. [predicate] must already
     * carry the type filter — see [typePredicate] (FR-5).
     */
    private fun assetsWithOptions(predicate: NSPredicate?): PHFetchResult = PHAsset.fetchAssetsWithOptions(optionsWithPredicate(predicate))

    /** Fetch options carrying [predicate] (null = unrestricted). */
    private fun optionsWithPredicate(predicate: NSPredicate?): PHFetchOptions {
        val options = PHFetchOptions()
        options.predicate = predicate
        return options
    }

    /**
     * The FR-5 type predicate for a fetch: `mediaType == image` (Live Photos are images
     * to PhotoKit, FR-5) or `mediaType == video`; null for ALL. This is the half that
     * [andPredicates] ANDs into **every** source predicate.
     */
    private fun typePredicate(type: MediaTypeFilter): NSPredicate? =
        when (type) {
            MediaTypeFilter.ALL -> null
            MediaTypeFilter.PHOTOS -> NSPredicate.predicateWithFormat("mediaType == ${PHAssetMediaTypeImage.toInt()}")
            MediaTypeFilter.VIDEOS -> NSPredicate.predicateWithFormat("mediaType == ${PHAssetMediaTypeVideo.toInt()}")
        }

    /** AND-combines the non-null predicates (FR-5); null when nothing restricts. */
    private fun andPredicates(vararg predicates: NSPredicate?): NSPredicate? =
        predicates.filterNotNull().let { parts ->
            when (parts.size) {
                0 -> null
                1 -> parts.single()
                else -> NSCompoundPredicate.andPredicateWithSubpredicates(parts)
            }
        }

    /**
     * FR-6 Screenshots: the `photoScreenshot` subtype bit ANDed with the type filter
     * (FR-5) — `(mediaSubtypes & PHAssetMediaSubtypePhotoScreenshot) != 0`.
     */
    private fun screenshotPredicate(type: MediaTypeFilter): NSPredicate? =
        andPredicates(
            typePredicate(type),
            NSPredicate.predicateWithFormat("mediaSubtypes & ${PHAssetMediaSubtypePhotoScreenshot.toLong()} != 0"),
        )

    /**
     * FR-6 Long videos: `mediaType == video AND duration >= 60` — the threshold comes
     * from [LONG_VIDEO_MIN_MS] so the fake and the predicate cannot drift; the compare
     * is inclusive at exactly 60 s. ANDed with the type filter (FR-5), so
     * `PHOTOS` yields nothing here, as it must.
     */
    private fun longVideoPredicate(type: MediaTypeFilter): NSPredicate? =
        andPredicates(
            typePredicate(type),
            NSPredicate.predicateWithFormat(
                "mediaType == ${PHAssetMediaTypeVideo.toInt()} AND duration >= ${LONG_VIDEO_MIN_MS / MS_PER_SECOND}",
            ),
        )

    /**
     * FR-6 Bursts: ids of every asset with a `burstIdentifier`. **Predicate support,
     * checked against the iOS 26.5 SDK:** `PHAsset.h` exposes `burstIdentifier` /
     * `representsBurst` only as plain read-only properties — neither is a documented
     * `NSPredicate` key path for `PHFetchOptions` (PhotoKit's KVC predicate support
     * covers mediaType/mediaSubtypes/creationDate/duration/pixel dimensions, not burst
     * identity), and the only burst-aware fetch hooks are `includeAllBurstAssets` on
     * the options and `fetchAssetsWithBurstIdentifier:` (which needs a known id). So:
     * fetch with `includeAllBurstAssets = true` — every frame of every burst, each
     * carrying its `burstIdentifier` — and filter in the enumeration on
     * `burstIdentifier != null`, ANDing the type filter into the fetch predicate (FR-5).
     * Creation-date descending via a sort descriptor, like every non-LONG_VIDEOS deck
     * (§6.5). Cost: one pass over the fetch result reading one property per asset —
     * the same order of work [duplicateCandidates] does.
     */
    private fun burstAssetIds(type: MediaTypeFilter): List<String> {
        val options = optionsWithPredicate(typePredicate(type))
        options.includeAllBurstAssets = true
        val sort = NSSortDescriptor(key = "creationDate", ascending = false)
        options.sortDescriptors = listOf(sort)
        val ids = mutableListOf<String>()
        PHAsset.fetchAssetsWithOptions(options).enumerateObjectsUsingBlock { asset, _, _ ->
            (asset as? PHAsset)?.let { if (it.burstIdentifier != null) ids += it.localIdentifier }
        }
        return ids
    }

    /** The `smartAlbumSelfPortraits` collection (FR-6), or null when PhotoKit has none. */
    private fun selfiesCollection(): PHAssetCollection? {
        val fetch =
            PHAssetCollection.fetchAssetCollectionsWithType(
                PHAssetCollectionTypeSmartAlbum,
                PHAssetCollectionSubtypeSmartAlbumSelfPortraits,
                null,
            )
        return fetch.firstObject() as? PHAssetCollection
    }

    /** One [AlbumSource] row (FR-8): cheap count + newest asset as cover; see [albums]. */
    private fun toAlbumSource(collection: PHAssetCollection): AlbumSource {
        val estimated = collection.estimatedAssetCount
        val count =
            if (estimated.toLong() == Long.MAX_VALUE) {
                PHAsset.fetchAssetsInAssetCollection(collection, null).count.toInt()
            } else {
                estimated.toInt()
            }
        val coverOptions = optionsWithPredicate(null)
        coverOptions.sortDescriptors = listOf(NSSortDescriptor(key = "creationDate", ascending = false))
        val cover = PHAsset.fetchAssetsInAssetCollection(collection, coverOptions).firstObject() as? PHAsset
        return AlbumSource(
            id = collection.localIdentifier,
            title = collection.localizedTitle ?: "",
            count = count,
            coverAssetId = cover?.localIdentifier,
        )
    }

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
