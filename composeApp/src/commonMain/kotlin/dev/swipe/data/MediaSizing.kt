package dev.swipe.data

import dev.swipe.domain.model.StorageEstimate

/**
 * Estimates the on-disk size of library items for the FR-34 line on the delete
 * confirmation. The iOS implementation (`platform/PhotoKitMediaSizing.kt`) sums
 * each item's `PHAssetResource` file sizes — **undocumented KVC** (Risk R3) — on
 * `Dispatchers.Default`, so the read never touches the main thread and never
 * downloads an original: only the resource metadata is read.
 *
 * Sizing is a side channel, not a gate: nothing awaits it before deleting
 * (NFR-5), and every failure mode — a missing asset, a resource whose size KVC
 * will not answer, a PhotoKit error — degrades the [StorageEstimate] instead of
 * throwing at the caller.
 */
interface MediaSizing {
    /**
     * Sum of [ids]' resource sizes. [StorageEstimate.Complete] when every size
     * was read, [StorageEstimate.Partial] with the lower bound when some were
     * not, [StorageEstimate.Unavailable] when none could be. An empty [ids] is
     * [StorageEstimate.Complete] with 0. Callable from any coroutine context:
     * the implementation moves to its own dispatcher.
     */
    suspend fun estimateBytes(ids: List<String>): StorageEstimate
}
