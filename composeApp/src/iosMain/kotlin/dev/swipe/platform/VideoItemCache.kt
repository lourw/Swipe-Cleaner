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
 * an in-flight request (FR-29). Unlike image requests, player-item and paired-video
 * writes have **no `PHImageRequestID` to cancel** (§6.8): [cancel] drops the entry
 * (cancelling the awaiting side), and a late callback finds no live continuation.
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
 * as the deck moves on.
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

    /** Live Photo paired-video exports by asset id (FR-17), same deferred-as-entry pattern. */
    private val livePhotoVideos = mutableMapOf<String, Deferred<NSURL?>>()

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
        val request =
            mutex.withLock {
                livePhotoVideos.getOrPut(id) { loadScope.async { fetchLivePhotoVideo(id) } }
            }
        val url = request.await()
        if (url == null) mutex.withLock { if (livePhotoVideos[id] === request) livePhotoVideos.remove(id) }
        return url
    }

    /** Delete [id]'s exported temp file after its player is done with it (FR-17). */
    fun releaseLivePhotoVideo(id: String) {
        loadScope.launch {
            // Await an in-flight write before deleting: cancelling mid-write would
            // leak the file, and nothing else holds the id once the player is gone.
            val request = mutex.withLock { livePhotoVideos.remove(id) } ?: return@launch
            request.await()?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        }
    }

    private suspend fun fetchItem(id: String): AVPlayerItem? {
        val asset = fetchAsset(id) ?: return null
        val options =
            PHVideoRequestOptions().apply {
                networkAccessAllowed = settings.includeCloudOnly.value
            }
        return suspendCancellableCoroutine { cont ->
            PHImageManager.defaultManager().requestPlayerItemForVideo(asset, options) { playerItem, _ ->
                // Main-queue callback; a dead continuation means cancel() already
                // retired the id — the request itself has no cancel handle (§6.8).
                if (cont.isActive) cont.resume(playerItem)
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

/**
 * Temp-file name for the paired video of Live Photo [assetId] (FR-17). PHAsset local
 * identifiers contain slashes (`…/L0/001`), so underscores are escaped by doubling
 * and every other non-alphanumeric character (slashes included) becomes a single
 * underscore: the mapping is deterministic — the same id always yields the same name
 * — and injective, so distinct ids can never share one temp file.
 */
internal fun livePhotoTempFileName(assetId: String): String =
    "swipe-live-" +
        assetId
            .replace("_", "__")
            .map { if (it.isLetterOrDigit() || it == '-') it else '_' }
            .joinToString("") +
        ".mov"
