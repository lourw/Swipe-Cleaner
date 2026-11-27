@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.SensitivityAnalyzer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGSizeMake
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHImageContentModeAspectFit
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.SensitiveContentAnalysis.SCSensitivityAnalysisPolicyDisabled
import platform.SensitiveContentAnalysis.SCSensitivityAnalyzer
import platform.UIKit.UIImage
import kotlin.coroutines.resume

/**
 * The FR-33 iOS analyzer: asks Apple's on-device `SensitiveContentAnalysis` framework
 * whether an asset's image is flagged. Nothing leaves the device (NFR-6) — the image
 * request pins `networkAccessAllowed = false`, so even fetching the pixels never
 * reaches iCloud, and the analysis itself is a local CoreML call.
 *
 * Only ids inside the deck's prefetch window are ever passed here (hard rule 6):
 * [SensitiveFilter] screens current + next 3, never the whole library. Videos are
 * declined by media type before any request (FR-33; Live Photos are images).
 *
 * Fails open everywhere (FR-33): a gone asset, a non-image, a null image, a null
 * analysis (the framework reports failures through the error argument), or any
 * unexpected answer is false. Until the user adds the entitlement (👤) the policy
 * reads [SCSensitivityAnalysisPolicyDisabled] and [isAvailable] is false — the
 * expected simulator state, no dialog, no crash.
 *
 * The 512 px analysis decode is a transient bitmap outside `BitmapLruCache`:
 * full-screen NFR-3 budget untouched.
 */
class PhotoKitSensitivityAnalyzer(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SensitivityAnalyzer {
    private val analyzer = SCSensitivityAnalyzer()

    override fun isAvailable(): Boolean = analyzer.analysisPolicy != SCSensitivityAnalysisPolicyDisabled

    override suspend fun isSensitive(id: String): Boolean =
        withContext(dispatcher) {
            val image = smallImage(id)
            image != null && analyze(image)
        }

    /** The asset's pixels at analysis size, or null: gone id, video, or failed request (fail open). */
    private suspend fun smallImage(id: String): UIImage? {
        val asset = assetById(id)?.takeIf { it.mediaType == PHAssetMediaTypeImage }
        return asset?.let { requestSmallImage(it) }
    }

    private suspend fun requestSmallImage(asset: PHAsset): UIImage? =
        suspendCancellableCoroutine { continuation ->
            val manager = PHImageManager.defaultManager()
            val requestId =
                manager.requestImageForAsset(
                    asset,
                    CGSizeMake(ANALYSIS_PX, ANALYSIS_PX),
                    PHImageContentModeAspectFit,
                    analysisOptions(),
                ) { image, _ ->
                    // HighQualityFormat delivery: the handler answers exactly once, nil on failure.
                    continuation.resume(image)
                }
            continuation.invokeOnCancellation { manager.cancelImageRequest(requestId) }
        }

    /** Local-only small request: no iCloud fetch (NFR-6), one high-quality delivery. */
    private fun analysisOptions(): PHImageRequestOptions =
        PHImageRequestOptions().apply {
            deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
            resizeMode = PHImageRequestOptionsResizeModeFast
            networkAccessAllowed = false
            synchronous = false
        }

    /** True only when the framework returns an analysis flagged sensitive; null/error is false. */
    private suspend fun analyze(image: UIImage): Boolean {
        val cg = image.CGImage ?: return false
        return suspendCancellableCoroutine { continuation ->
            analyzer.analyzeCGImage(cg) { analysis, _ ->
                continuation.resume(analysis?.isSensitive() == true)
            }
        }
    }

    private companion object {
        /** Analysis target edge in pixels: small decode, never cached (NFR-3). */
        const val ANALYSIS_PX = 512.0
    }
}
