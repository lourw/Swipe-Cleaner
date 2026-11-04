@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.compareDatesDescending
import dev.swipe.data.monthRange
import dev.swipe.domain.model.AlbumSource
import dev.swipe.domain.model.DuplicateKey
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.usecase.DuplicateGrouper
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toNSDate
import platform.Foundation.NSPredicate
import platform.Foundation.NSSortDescriptor
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumSelfPortraits
import platform.Photos.PHAssetCollectionTypeSmartAlbum

/*
 * The per-source fetches behind the WP-5.1 source queries (FR-4–7), extracted from
 * PhotoKitMediaLibrary so that class stays under detekt's function budget. All run
 * inside the library's `fetchDispatcher` (`Dispatchers.Default`, §6.8) — these are
 * synchronous PhotoKit calls; the suspending entry points wrap them in `withContext`.
 */

/**
 * FR-4 / FR-8: the deck ids of one album — `fetchAssetsInAssetCollection` over the
 * collection carrying [id], type-filtered (FR-5), creation-date descending (§6.5:
 * the descriptor overrides the album's custom order). Unknown id → empty.
 */
internal fun albumAssetIds(
    id: String,
    type: MediaTypeFilter,
): List<String> {
    val collection =
        PHAssetCollection
            .fetchAssetCollectionsWithLocalIdentifiers(listOf(id), null)
            .firstObject() as? PHAssetCollection
            ?: return emptyList()
    val options = sortedOptionsWithPredicate(typePredicate(type), "creationDate")
    return idsFrom(PHAsset.fetchAssetsInAssetCollection(collection, options))
}

/**
 * FR-7: the deck ids created in one month — the half-open `[start, end)` range from
 * [monthRange] (device zone, matching the month buckets by construction) as a
 * `creationDate >= start AND creationDate < end` predicate, type-filtered (FR-5),
 * creation-date descending.
 */
internal fun monthAssetIds(
    year: Int,
    month: Int,
    type: MediaTypeFilter,
): List<String> {
    val (start, end) = monthRange(year, month, TimeZone.currentSystemDefault())
    val range =
        andPredicates(
            // Absolute-instant bounds matching the Kotlin bucketing by construction; NSDate's
            // double representation resolves ~100 ns at this epoch vs kotlinx microseconds,
            // so disagreement needs an asset created within ~0.1 µs of local month-midnight.
            NSPredicate.predicateWithFormat("creationDate >= %@", start.toNSDate()),
            NSPredicate.predicateWithFormat("creationDate < %@", end.toNSDate()),
        )
    val options = sortedOptionsWithPredicate(andPredicates(range, typePredicate(type)), "creationDate")
    return idsFrom(assetsWithOptions(options.predicate))
}

/**
 * FR-6: the deck ids of one smart filter, type-filtered (FR-5) and ordered per
 * §6.5 — Screenshots by subtype predicate, Long videos by duration predicate sorted
 * duration-descending, Bursts via [burstAssetIds], Selfies inside the
 * `smartAlbumSelfPortraits` collection (no album → empty), Likely duplicates through
 * the shared [DuplicateGrouper] over [duplicateCandidateKeys] (§6.5: subtracting
 * decided ids is `BuildDeckUseCase`'s job; this returns the raw group order).
 */
internal fun smartAssetIds(
    kind: SmartFilter,
    type: MediaTypeFilter,
): List<String> =
    when (kind) {
        SmartFilter.SCREENSHOTS -> {
            idsFrom(
                PHAsset.fetchAssetsWithOptions(
                    sortedOptionsWithPredicate(screenshotPredicate(type), "creationDate"),
                ),
            )
        }

        SmartFilter.LONG_VIDEOS -> {
            idsFrom(PHAsset.fetchAssetsWithOptions(sortedOptionsWithPredicate(longVideoPredicate(type), "duration")))
        }

        SmartFilter.BURSTS -> {
            burstAssetIds(type)
        }

        SmartFilter.SELFIES -> {
            val collection = selfiesCollection() ?: return emptyList()
            val options = sortedOptionsWithPredicate(typePredicate(type), "creationDate")
            idsFrom(PHAsset.fetchAssetsInAssetCollection(collection, options))
        }

        SmartFilter.LIKELY_DUPLICATES -> {
            DuplicateGrouper.group(duplicateCandidateKeys(type))
        }
    }

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
 * the same order of work [duplicateCandidateKeys] does.
 */
internal fun burstAssetIds(type: MediaTypeFilter): List<String> {
    val ids = mutableListOf<String>()
    forEachBurstId(type, sorted = true) { ids += it }
    return ids
}

/** Burst count without materializing ids — the `smartCounts` entry (NFR-4, no sort needed). */
internal fun countBurstAssets(type: MediaTypeFilter): Int {
    var count = 0
    forEachBurstId(type, sorted = false) { count++ }
    return count
}

private fun forEachBurstId(
    type: MediaTypeFilter,
    sorted: Boolean,
    body: (String) -> Unit,
) {
    val options = optionsWithPredicate(typePredicate(type))
    options.includeAllBurstAssets = true
    if (sorted) {
        options.sortDescriptors = listOf(NSSortDescriptor(key = "creationDate", ascending = false))
    }
    PHAsset.fetchAssetsWithOptions(options).enumerateObjectsUsingBlock { asset, _, _ ->
        (asset as? PHAsset)?.let { if (it.burstIdentifier != null) body(it.localIdentifier) }
    }
}

/** The `smartAlbumSelfPortraits` collection (FR-6), or null when PhotoKit has none. */
internal fun selfiesCollection(): PHAssetCollection? {
    val fetch =
        PHAssetCollection.fetchAssetCollectionsWithType(
            PHAssetCollectionTypeSmartAlbum,
            PHAssetCollectionSubtypeSmartAlbumSelfPortraits,
            null,
        )
    return fetch.firstObject() as? PHAssetCollection
}

/** One [AlbumSource] row (FR-8): cheap count + newest asset as cover. */
internal fun toAlbumSource(collection: PHAssetCollection): AlbumSource {
    val estimated = collection.estimatedAssetCount
    val count =
        if (estimated.toLong() == Long.MAX_VALUE) {
            PHAsset.fetchAssetsInAssetCollection(collection, null).count.toInt()
        } else {
            estimated.toInt()
        }
    val coverOptions = sortedOptionsWithPredicate(null, "creationDate")
    val cover = PHAsset.fetchAssetsInAssetCollection(collection, coverOptions).firstObject() as? PHAsset
    return AlbumSource(
        id = collection.localIdentifier,
        title = collection.localizedTitle ?: "",
        count = count,
        coverAssetId = cover?.localIdentifier,
    )
}

/**
 * Cheap candidates for the Likely Duplicates filter (FR-6, §6.5): enumerates **only**
 * `creationDate`, `pixelWidth`, and `pixelHeight` off the fetch result — the three
 * fields [DuplicateKey] carries, nothing else (NFR-4) — sorted by creation date
 * descending (nulls last), matching the fake and what [DuplicateGrouper] expects
 * (§6.4: date-sorted; §6.5: newest-first deck order).
 */
internal fun duplicateCandidateKeys(type: MediaTypeFilter): List<DuplicateKey> {
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
    keys.sortWith { a, b -> compareDatesDescending(a.createdAt, b.createdAt) }
    return keys
}
