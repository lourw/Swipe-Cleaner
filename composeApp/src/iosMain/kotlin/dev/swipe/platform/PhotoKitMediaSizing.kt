@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.MediaSizing
import dev.swipe.domain.model.StorageEstimate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSNumber
import platform.Foundation.valueForKey
import platform.Photos.PHAssetResource
import platform.darwin.NSObject

/**
 * iOS [MediaSizing] (FR-34): sums each item's `PHAssetResource` file sizes and
 * returns the queue's [StorageEstimate].
 *
 * **The read (Risk R3).** `fileSize` is not in PhotoKit's declared API — it is
 * read through KVC (`valueForKey("fileSize")` → `NSNumber.longLongValue`), the
 * only way to size an asset resource at all. A key PhotoKit ever stops
 * answering throws or returns nil, and a resource answering 0 is treated as
 * unreadable too — a 0-byte photo is never honest. Every such item counts as
 * unreadable, which is what makes the estimate [StorageEstimate.Partial] or,
 * when nothing reads at all, [StorageEstimate.Unavailable].
 *
 * **Cost.** Only resource *metadata* is read — no data is requested, so nothing
 * downloads (NFR-6: PhotoKit's own iCloud downloads stay the only network
 * path). The whole sum runs on [ioDispatcher] ([Dispatchers.Default] by
 * default, per the FR-34 spec), including the id → asset fetches
 * ([assetById], the one shared burst-inclusive lookup, FR-6).
 *
 * **The delete path never sees this class** (NFR-5): the estimate feeds the
 * confirmation line alone.
 */
class PhotoKitMediaSizing(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaSizing {
    override suspend fun estimateBytes(ids: List<String>): StorageEstimate = withContext(ioDispatcher) { sumQueue(ids) }

    /** Every id sized → [StorageEstimate.Complete]; some → [StorageEstimate.Partial]; none → [StorageEstimate.Unavailable]. */
    private fun sumQueue(ids: List<String>): StorageEstimate {
        if (ids.isEmpty()) return StorageEstimate.Complete(totalBytes = 0L)
        var total = 0L
        var unreadable = 0
        for (id in ids) {
            val bytes = sizeOfAsset(id)
            if (bytes == null) unreadable++ else total += bytes
        }
        return when (unreadable) {
            0 -> StorageEstimate.Complete(total)
            ids.size -> StorageEstimate.Unavailable
            else -> StorageEstimate.Partial(total)
        }
    }

    /** [id]'s resource sizes summed; null when the asset is gone or any of its resources reads no size. */
    private fun sizeOfAsset(id: String): Long? {
        val asset = assetById(id) ?: return null
        val resources = PHAssetResource.assetResourcesForAsset(asset).filterIsInstance<PHAssetResource>()
        if (resources.isEmpty()) return null
        var total = 0L
        for (resource in resources) {
            val bytes = fileSizeOf(resource) ?: return null
            total += bytes
        }
        return total
    }

    /** The undocumented-KVC read (R3); null when the key throws, is absent, or answers 0. */
    private fun fileSizeOf(resource: PHAssetResource): Long? =
        runCatching { ((resource as? NSObject)?.valueForKey(FILE_SIZE_KEY) as? NSNumber)?.longLongValue }
            .getOrNull()
            ?.takeIf { it > 0L }

    private companion object {
        /** `PHAssetResource.fileSize` — not a declared property, hence KVC (Risk R3). */
        const val FILE_SIZE_KEY = "fileSize"
    }
}
