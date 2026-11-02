@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import dev.swipe.data.LoadedImage
import dev.swipe.data.MediaLoader
import dev.swipe.data.SettingsStore
import dev.swipe.data.image.BitmapCacheKey
import dev.swipe.data.image.BitmapLruCache
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Photos.PHAsset
import platform.Photos.PHCachingImageManager
import platform.Photos.PHImageCancelledKey
import platform.Photos.PHImageContentModeAspectFit
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeOpportunistic
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.Photos.PHImageResultIsDegradedKey
import platform.Photos.PHImageResultIsInCloudKey
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIScreen
import platform.posix.memcpy
import kotlin.math.roundToInt

/**
 * PhotoKit-backed [MediaLoader] (WP-1.5; §6.5 step 3, NFR-1–3, R1, R6).
 *
 * **Request shape.** `PHCachingImageManager.requestImageForAsset` with
 * `deliveryMode = Opportunistic` (degraded first, then full — NFR-1),
 * `resizeMode = Fast`, `contentMode = AspectFit`, and
 * `networkAccessAllowed = SettingsStore.includeCloudOnly` read when the request starts,
 * so flipping the setting can't change an in-flight request (FR-29). The info dict's
 * `PHImageResultIsDegradedKey` / `PHImageResultIsInCloudKey` become
 * [LoadedImage.isDegraded] / [LoadedImage.isCloud] (R6). The flow closes after the
 * full-quality result; `PHImageCancelledKey` callbacks are ignored (§6.8).
 *
 * **Threading (R1, NFR-2).** PhotoKit delivers results on the main queue, so the handler
 * only queues the `UIImage` and the flow's decoder coroutine — on [Dispatchers.Default] —
 * re-encodes it to JPEG (`UIImageJPEGRepresentation`) and Skia-decodes it via
 * `Image.makeFromEncoded(...).toComposeImageBitmap()`. Bytes choice: the already-rasterised
 * `UIImage` at the requested size keeps the round-trip bounded by the card, whereas
 * `requestImageDataAndOrientation` would pull *original* HEIC/JPEG data (tens of MB per
 * card) — far more than a card shows. Cancelling the collector cancels the
 * `PHImageRequestID` in `awaitClose`.
 *
 * **Memory (NFR-3).** Every full-size (non-degraded) decode goes through [cache]
 * (`BitmapLruCache`, capacity 5); evicted bitmaps are closed by the cache's `onEvict`.
 * `targetPx` is capped at the screen's pixel size (main screen bounds × scale): decoding
 * more pixels than the display can show would spend memory and CPU for nothing (NFR-2,
 * R1). The app is portrait-only (§6.8) and single-window, so the portrait bounds are
 * constant after launch; [UIScreen] is read lazily (synchronized) from whichever thread
 * first requests an image, which is safe for these launch-frozen values even though
 * UIKit nominally prefers the main thread.
 */
class PhotoKitMediaLoader(
    private val settings: SettingsStore,
    private val cache: BitmapLruCache<ImageBitmap>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaLoader {
    private val manager = PHCachingImageManager()

    /** For the fire-and-forget prefetch calls, which must not run PhotoKit on main (§6.8). */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    override fun image(
        id: String,
        targetPx: IntSize,
    ): Flow<LoadedImage> =
        callbackFlow {
            val networkAllowed = settings.includeCloudOnly.first()
            val size = capToScreen(targetPx)
            val key = BitmapCacheKey(id, size)

            // A cached full-size decode at this exact key is the sharp result already (NFR-3):
            // emit it without touching PhotoKit. Its pixels are local, so isCloud is false.
            cache.get(key)?.let { decoded ->
                trySend(LoadedImage(decoded, isDegraded = false, isCloud = false))
                close()
                return@callbackFlow
            }

            val asset = withContext(ioDispatcher) { PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null).firstObject as? PHAsset }
            if (asset == null) {
                // Deleted outside the app between deck build and load: complete with nothing.
                close()
                return@callbackFlow
            }

            val results = Channel<RawDelivery>(Channel.UNLIMITED)
            val options = requestOptions(networkAllowed)
            // PhotoKit calls run on Dispatchers.Default (AGENTS.md); the call is async and
            // cheap, but the collector is the composition (main). requestId is still
            // assigned before awaitClose exists, so cancellation stays race-free.
            val requestId =
                withContext(ioDispatcher) {
                    manager.requestImageForAsset(asset, size.toCGSize(), PHImageContentModeAspectFit, options) { image, info ->
                        // Main queue: never decode here (NFR-2). A cancelled delivery is the
                        // collector leaving — ignore it (§6.8).
                        val values = info.orEmpty()
                        if (values.flag(PHImageCancelledKey)) return@requestImageForAsset
                        results.trySend(RawDelivery(image, values))
                    }
                }

            // Decoder loop: keeps the degraded → full delivery order NFR-1 promises, and it
            // is a child of the producer scope, so a cancelled collector cancels it too.
            // Ends after the first non-degraded delivery, decoded or not: a nil image there
            // is PHImageErrorKey (download failed) or the asset vanishing mid-request, and
            // nothing more is coming either way.
            launch(ioDispatcher) {
                var complete = false
                while (!complete) {
                    val delivery = results.receive()
                    val isDegraded = delivery.info.flag(PHImageResultIsDegradedKey)
                    val isCloud = delivery.info.flag(PHImageResultIsInCloudKey)
                    val decoded = delivery.image?.let { raw -> runCatching { raw.decodeToBitmap() }.getOrNull() }
                    if (decoded != null) {
                        // Only full-size decodes enter the LRU (NFR-3 budgets full-screen
                        // bitmaps); small degraded deliveries are left to Skia's finalizer.
                        if (!isDegraded) cache.put(key, decoded)
                        send(LoadedImage(decoded, isDegraded = isDegraded, isCloud = isCloud))
                    }
                    complete = !isDegraded
                }
                close()
            }

            awaitClose { manager.cancelImageRequest(requestId) }
        }

    override fun prefetch(
        ids: List<String>,
        targetPx: IntSize,
    ) {
        if (ids.isEmpty()) return
        startOrStopCaching(ids, targetPx, start = true)
    }

    override fun cancelPrefetch(
        ids: List<String>,
        targetPx: IntSize,
    ) {
        if (ids.isEmpty()) return
        startOrStopCaching(ids, targetPx, start = false)
    }

    /**
     * `startCachingImagesForAssets` / `stopCachingImagesForAssets` with the same size and
     * options as [image] — PhotoKit matches cached images by asset + size + content mode,
     * so stop only undoes start if both cap [targetPx] identically (§6.5 step 3). The
     * id → PHAsset fetch is a keyed lookup, cheap even for a large library (NFR-4).
     */
    private fun startOrStopCaching(
        ids: List<String>,
        targetPx: IntSize,
        start: Boolean,
    ) {
        val size = capToScreen(targetPx)
        val options = requestOptions(settings.includeCloudOnly.value)
        scope.launch {
            val fetched = PHAsset.fetchAssetsWithLocalIdentifiers(ids, null)
            val assets = ArrayList<PHAsset>(fetched.count.toInt())
            fetched.enumerateObjectsUsingBlock { asset, _, _ ->
                (asset as? PHAsset)?.let(assets::add)
            }
            if (assets.isEmpty()) return@launch
            val cgSize = size.toCGSize()
            if (start) {
                manager.startCachingImagesForAssets(assets, cgSize, PHImageContentModeAspectFit, options)
            } else {
                manager.stopCachingImagesForAssets(assets, cgSize, PHImageContentModeAspectFit, options)
            }
        }
    }

    /** The one request shape from §6.5 step 3, shared by [image] and the prefetch calls. */
    private fun requestOptions(networkAllowed: Boolean): PHImageRequestOptions =
        PHImageRequestOptions().apply {
            deliveryMode = PHImageRequestOptionsDeliveryModeOpportunistic
            resizeMode = PHImageRequestOptionsResizeModeFast
            networkAccessAllowed = networkAllowed
        }

    /** Re-encode + Skia decode off the main thread; see the class doc for the bytes choice. */
    private fun UIImage.decodeToBitmap(): ImageBitmap {
        val data = UIImageJPEGRepresentation(this, JPEG_QUALITY) ?: error("UIImageJPEGRepresentation returned nil")
        return Image.makeFromEncoded(data.toByteArray()).toComposeImageBitmap()
    }

    /** `targetPx` with each side capped at the screen's pixel size (NFR-2, R1). */
    private fun capToScreen(targetPx: IntSize): IntSize {
        val screen = screenPixelSize
        return IntSize(targetPx.width.coerceAtMost(screen.width), targetPx.height.coerceAtMost(screen.height))
    }

    /** Main screen bounds × scale, in pixels; portrait-only app, so stable across a session. */
    private val screenPixelSize: IntSize by lazy {
        val screen = UIScreen.mainScreen
        screen.bounds.useContents {
            IntSize(
                width = (size.width * screen.scale).roundToInt(),
                height = (size.height * screen.scale).roundToInt(),
            )
        }
    }

    /** One PhotoKit delivery: the image (null on error) and the info dict flags. */
    private class RawDelivery(
        val image: UIImage?,
        val info: Map<Any?, *>,
    )

    /** NSData → ByteArray by copying the buffer; [ExperimentalForeignApi] via memcpy. */
    private fun NSData.toByteArray(): ByteArray {
        val count = length.toInt()
        if (count == 0) return ByteArray(0)
        val bytes = ByteArray(count)
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
        return bytes
    }

    private companion object {
        /** Re-encode quality for the off-main Skia hand-off; visually lossless for a card. */
        const val JPEG_QUALITY = 0.9

        /** PhotoKit's BOOL info-dict flags arrive as NSNumber; missing key reads false. */
        fun Map<Any?, *>.flag(key: String): Boolean = (this[key] as? NSNumber)?.boolValue == true

        fun IntSize.toCGSize(): CValue<CGSize> = CGSizeMake(width.toDouble(), height.toDouble())
    }
}
