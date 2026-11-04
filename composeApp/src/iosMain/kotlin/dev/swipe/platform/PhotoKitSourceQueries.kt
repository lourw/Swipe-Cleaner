@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.LONG_VIDEO_MIN_MS
import dev.swipe.domain.model.MediaTypeFilter
import platform.Foundation.NSCompoundPredicate
import platform.Foundation.NSPredicate
import platform.Foundation.NSSortDescriptor
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaSubtypePhotoScreenshot
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult

/*
 * The predicate and fetch-option vocabulary behind the WP-5.1 source queries
 * (FR-5, FR-6), extracted from PhotoKitMediaLibrary so that class stays under detekt's
 * function budget. Every predicate here carries the FR-5 type filter — the spec's rule
 * that the media-type toggle applies on top of *any* source is enforced at this
 * chokepoint: sources only ever fetch through these builders.
 */

/** NSTimeInterval (seconds) → milliseconds. */
private const val MS_PER_SECOND = 1000.0

/**
 * A fetch of every asset matching [predicate], library-wide. [predicate] must already
 * carry the type filter — see [typePredicate] (FR-5).
 */
internal fun assetsWithOptions(predicate: NSPredicate?): PHFetchResult = PHAsset.fetchAssetsWithOptions(optionsWithPredicate(predicate))

/** Fetch options carrying [predicate] (null = unrestricted). */
internal fun optionsWithPredicate(predicate: NSPredicate?): PHFetchOptions {
    val options = PHFetchOptions()
    options.predicate = predicate
    return options
}

/** Fetch options sorted by [sortKey] descending (§6.5 deck order) + [predicate]. */
internal fun sortedOptionsWithPredicate(
    predicate: NSPredicate?,
    sortKey: String,
): PHFetchOptions = optionsWithPredicate(predicate).apply { sortDescriptors = listOf(NSSortDescriptor(key = sortKey, ascending = false)) }

/** The ids of a fetch result — `localIdentifier` is the only property touched (NFR-4). */
internal fun idsFrom(result: PHFetchResult): List<String> {
    val ids = ArrayList<String>(result.count.toInt())
    result.enumerateObjectsUsingBlock { asset, _, _ ->
        (asset as? PHAsset)?.localIdentifier?.let(ids::add)
    }
    return ids
}

/**
 * The FR-5 type predicate for a fetch: `mediaType == image` (Live Photos are images
 * to PhotoKit, FR-5) or `mediaType == video`; null for ALL. This is the half that
 * [andPredicates] ANDs into **every** source predicate.
 */
internal fun typePredicate(type: MediaTypeFilter): NSPredicate? =
    when (type) {
        MediaTypeFilter.ALL -> null
        MediaTypeFilter.PHOTOS -> NSPredicate.predicateWithFormat("mediaType == ${PHAssetMediaTypeImage.toInt()}")
        MediaTypeFilter.VIDEOS -> NSPredicate.predicateWithFormat("mediaType == ${PHAssetMediaTypeVideo.toInt()}")
    }

/** AND-combines the non-null predicates (FR-5); null when nothing restricts. */
internal fun andPredicates(vararg predicates: NSPredicate?): NSPredicate? =
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
internal fun screenshotPredicate(type: MediaTypeFilter): NSPredicate? =
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
internal fun longVideoPredicate(type: MediaTypeFilter): NSPredicate? =
    andPredicates(
        typePredicate(type),
        NSPredicate.predicateWithFormat(
            "mediaType == ${PHAssetMediaTypeVideo.toInt()} AND duration >= ${LONG_VIDEO_MIN_MS / MS_PER_SECOND}",
        ),
    )
