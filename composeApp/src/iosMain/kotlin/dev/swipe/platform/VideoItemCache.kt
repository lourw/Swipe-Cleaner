@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.SettingsStore
import dev.swipe.data.VideoPreloader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVPlayerItem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHImageManager
import platform.Photos.PHVideoRequestOptions
import kotlin.coroutines.resume

/**
 * The deck's warm playable-video cache (WP-4.1; §6.5 step 4, FR-16, FR-17): one
 * in-flight-or-completed `AVPlayerItem` request per asset id, fed ahead of the card
 * by [preload] and read by the `VideoPlayer` actual. A completed request *is* the
 * cache entry, so a warm id never re-requests.
 *
 * **Request shape.** `PHImageManager.requestPlayerItemForVideo` with
 * `networkAccessAllowed = SettingsStore.includeCloudOnly` read when the request
 * starts — [PhotoKitMediaLoader]'s handling, so flipping the setting cannot change
 * an in-flight request (FR-29). The call returns a `PHImageRequestID`, so
 * cancelling a departed id also calls `cancelImageRequest` — an in-flight iCloud
 * video download stops instead of finishing to be discarded (§6.8). Only the
 * paired-video write (`writeDataForAssetResource`) has no cancel handle; its
 * awaiting coroutine just drops the result.
 *
 * **Threading (AGENTS.md, NFR-2).** Asset fetches and the PhotoKit calls run on
 * [ioDispatcher]; result handlers arrive on the main queue and only resume a
 * continuation.
 *
 * **Failure.** A nil result (deleted asset, refused cloud download) is *not* cached:
 * the next read retries, because an iCloud-only item may succeed once the download
 * is allowed (R6), and caching nil would pin the failure for the session.
 *
 * **Memory (NFR-3).** Entries are bounded by the deck's prefetch window (4 ids) plus
 * one mounted Live Photo; [cancel] and [releaseLivePhotoVideo] retire entries as soon
 * as the deck moves on. The bound is caller discipline, not internal eviction — a
 * real bound (LRU or window-only eviction) is deferred to the WP-6.3 perf pass.
 */
class VideoItemCache(
    private val settings: SettingsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : VideoPreloader {
    /** Shared scope for cache loads and the player view's item fetch (§6.5 step 4). */
    internal val loadScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutex = Mutex()

    /** One in-flight-or-completed player-item request per asset id (§6.5 step 4). */
    private val items = mutableMapOf<String, Deferred<AVPlayerItem?>>()

    /**
     * Live Photo paired-video exports by asset id (FR-17), same deferred-as-entry
     * pattern plus a mount count: a re-mount that races a release keeps its file
     * (the delete only fires when the last mount lets go).
     */
    private class LivePhotoEntry(
        val request: Deferred<NSURL?>,
    ) {
        var mounts = 0
    }

    private val livePhotoVideos = mutableMapOf<String, LivePhotoEntry>()

    override fun preload(id: String) {
        // Fire-and-forget like MediaLoader.prefetch; a warm id returns from the map.
        loadScope.launch { item(id) }
    }

    override fun cancel(id: String) {
        loadScope.launch { mutex.withLock { items.remove(id) }?.cancel() }
    }

    /**
     * The playable item for [id] — the warm cache entry when there is one, otherwise
     * the request is started here and awaited. Null when the asset is gone or the
     * item cannot be produced (the caller keeps showing the poster frame).
     */
    suspend fun item(id: String): AVPlayerItem? {
        val request = mutex.withLock { items.getOrPut(id) { loadScope.async { fetchItem(id) } } }
        val playerItem = request.await()
        if (playerItem == null) {
            mutex.withLock { if (items[id] === request) items.remove(id) }
        }
        return playerItem
    }

    /**
     * The paired-video file of Live Photo [id] exported to a temp file (FR-17):
     * `PHAssetResourceManager.writeDataForAssetResource` of the
     * `PHAssetResourceTypePairedVideo` resource — the only public way to the motion
     * payload. Cached by id until [releaseLivePhotoVideo] deletes the file.
     */
    suspend fun livePhotoVideo(id: String): NSURL? {
        val entry =
            mutex.withLock {
                livePhotoVideos
                    .getOrPut(id) { LivePhotoEntry(loadScope.async { fetchLivePhotoVideo(id) }) }
                    .also { it.mounts++ }
            }
        val url = entry.request.await()
        if (url == null) {
            mutex.withLock {
                entry.mounts--
                if (entry.mounts <= 0 && livePhotoVideos[id] === entry) livePhotoVideos.remove(id)
            }
        }
        return url
    }

    /** Delete [id]'s exported temp file after its player is done with it (FR-17). */
    fun releaseLivePhotoVideo(id: String) {
        loadScope.launch {
            val entry = mutex.withLock { livePhotoVideos[id] } ?: return@launch
            // Await an in-flight write before deleting: cancelling mid-write would
            // leak the file, and nothing else holds the id once the player is gone.
            val url = entry.request.await()
            val retire =
                mutex.withLock {
                    entry.mounts--
                    // Delete only the file nobody has re-mounted since: a fresh
                    // mount reused (or replaced) this entry and still needs it.
                    if (entry.mounts <= 0 && livePhotoVideos[id] === entry) livePhotoVideos.remove(id) else null
                }
            if (retire != null) url?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        }
    }

    private suspend fun fetchItem(id: String): AVPlayerItem? {
        val asset = fetchAsset(id) ?: return null
        val options =
            PHVideoRequestOptions().apply {
                networkAccessAllowed = settings.includeCloudOnly.value
            }
        return suspendCancellableCoroutine { cont ->
            val requestId =
                PHImageManager
                    .defaultManager()
                    .requestPlayerItemForVideo(asset, options) { playerItem, _ ->
                        // Main-queue callback; may deliver a degraded item before the
                        // final one, so only the first resume wins.
                        if (cont.isActive) cont.resume(playerItem)
                    }
            // cancel(id)/scope teardown reaches the PhotoKit request too, not just the
            // awaiting coroutine — an in-flight iCloud download stops here (§6.8).
            cont.invokeOnCancellation {
                PHImageManager.defaultManager().cancelImageRequest(requestId)
            }
        }
    }

    private suspend fun fetchLivePhotoVideo(id: String): NSURL? {
        val paired = fetchAsset(id)?.let(::pairedVideoResource) ?: return null
        val fileURL = NSURL.fileURLWithPath(NSTemporaryDirectory() + livePhotoTempFileName(id))
        // writeDataForAssetResource demands a destination that does not exist yet;
        // a leftover from a crashed session is replaced, not reused.
        NSFileManager.defaultManager.removeItemAtURL(fileURL, null)
        return suspendCancellableCoroutine { cont ->
            PHAssetResourceManager
                .defaultManager()
                .writeDataForAssetResource(paired, fileURL, null) { error ->
                    // A failed export can leave a partial file; drop it so it does
                    // not squat in temp until the same id is retried.
                    if (error != null) NSFileManager.defaultManager.removeItemAtURL(fileURL, null)
                    if (cont.isActive) cont.resume(if (error == null) fileURL else null)
                }
        }
    }

    private suspend fun fetchAsset(id: String): PHAsset? =
        withContext(ioDispatcher) {
            PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null).firstObject as? PHAsset
        }

    /** The paired-video resource of a Live Photo asset (FR-17); null for other assets. */
    private fun pairedVideoResource(asset: PHAsset): PHAssetResource? =
        PHAssetResource
            .assetResourcesForAsset(asset)
            .filterIsInstance<PHAssetResource>()
            .firstOrNull { it.type == PHAssetResourceTypePairedVideo }
}

/** Hex-encoding knobs for [livePhotoTempFileName]'s escape (FR-17). */
private const val HEX_RADIX = 16
private const val HEX_ESCAPE_WIDTH = 4

/**
 * Temp-file name for the paired video of Live Photo [assetId] (FR-17). PHAsset local
 * identifiers contain slashes (`…/L0/001`), so every non-alphanumeric character
 * except `-` is encoded as `_` plus its four-hex-digit code point and the rest pass
 * through. The encoding is injective — `_` only ever appears as the start of a
 * fixed-width escape, so even consecutive specials cannot make two distinct ids
 * share one temp file (`a//b` vs `a_b` collide under naive flattening, not here).
 */
internal fun livePhotoTempFileName(assetId: String): String =
    "swipe-live-" +
        buildString {
            assetId.forEach { c ->
                if (c.isLetterOrDigit() || c == '-') {
                    append(c)
                } else {
                    append('_').append(c.code.toString(HEX_RADIX).padStart(HEX_ESCAPE_WIDTH, '0'))
                }
            }
        } +
        ".mov"
