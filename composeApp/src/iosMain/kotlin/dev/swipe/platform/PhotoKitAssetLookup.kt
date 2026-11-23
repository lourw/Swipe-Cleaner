@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import platform.Photos.PHAsset
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult

/**
 * The one id → assets lookup every PhotoKit call site shares (FR-6, WP-8.3).
 *
 * The empty Bursts deck: the source fetches enumerate **every** burst frame
 * with `includeAllBurstAssets = true` (see [burstAssetIds]), while the id
 * lookups passed nil options — PhotoKit's default view, which carries only one
 * representative per burst — so the frames the deck enumerated resolved to
 * nothing downstream (metadata, images, video items, shares, deletes). Every
 * lookup by `localIdentifier` therefore goes through here with the same
 * burst-inclusive option, so any id a source query produced stays resolvable.
 *
 * Whether `includeAllBurstAssets` applies to `fetchAssetsWithLocalIdentifiers`
 * is not documented (WP-8.3); the device checkpoint with real bursts confirms
 * it. If it turns out not to, the fallback is `fetchAssetsWithBurstIdentifier`
 * per frame — not dropping the option again.
 *
 * Returns null when the fetch itself fails, so callers can separate "PhotoKit
 * could not answer" from "no such assets" (audit R1): the binding types the
 * result non-null although the ObjC call answers nil on error, and
 * [runCatching] turns that failure into a null the caller must handle.
 * [fetch] is the options-capture seam the fetch-options tests use (WP-7.7
 * pattern); production callers never pass it.
 */
internal fun fetchAssetsByIds(
    ids: List<String>,
    fetch: (List<String>, PHFetchOptions) -> PHFetchResult? = { identifiers, options ->
        runCatching { PHAsset.fetchAssetsWithLocalIdentifiers(identifiers, options) }.getOrNull()
    },
): PHFetchResult? = fetch(ids, burstInclusiveIdFetchOptions())

/** The single-asset form of [fetchAssetsByIds]; null when the asset is gone or the fetch fails. */
internal fun assetById(id: String): PHAsset? = fetchAssetsByIds(listOf(id))?.firstObject as? PHAsset

/** Id-lookup options carrying every burst frame, matching what the source fetches enumerate (FR-6). */
internal fun burstInclusiveIdFetchOptions(): PHFetchOptions = PHFetchOptions().apply { includeAllBurstAssets = true }
