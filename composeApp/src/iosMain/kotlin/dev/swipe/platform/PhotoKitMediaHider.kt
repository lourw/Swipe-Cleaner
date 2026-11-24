package dev.swipe.platform

import dev.swipe.data.MediaHider
import dev.swipe.domain.model.HideResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

/**
 * The FR-38 hide write door (WP-8.11): resolves [id] through the shared burst-inclusive
 * [assetById] lookup — the same one metadata, images, shares, and deletes use, so any id
 * a source query produced stays resolvable — and flips the asset's `hidden` flag with
 * `performChanges { PHAssetChangeRequest(forAsset).hidden = … }`, bridged by
 * [suspendCancellableCoroutine] like the delete path (one PhotoKit change request,
 * completion answered on a PhotoKit queue).
 *
 * Unlike a delete this shows **no iOS system dialog** — hiding is an ordinary metadata
 * change — and it never reaches `deleteAssets`: the §8.3 check-rules guard keeps this
 * file's [PHPhotoLibrary.performChanges] call the only write site besides
 * `PhotoKitMediaLibrary`'s delete batch, and the change block names only `hidden`.
 *
 * An unfetchable id (deleted, outside a Limited selection) answers [HideResult.FAILED]
 * without a change request, mirroring the FR-26 rule that unresolved ids are reported,
 * never silently applied. Once the change request has started, cancelling the caller
 * cancels the wait, not the change (PhotoKit offers no handle to the running request).
 */
class PhotoKitMediaHider(
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaHider {
    override suspend fun setHidden(
        id: String,
        hidden: Boolean,
    ): HideResult =
        withContext(writeDispatcher) {
            val asset = assetById(id) ?: return@withContext HideResult.FAILED
            suspendCancellableCoroutine { continuation ->
                PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                    changeBlock = {
                        PHAssetChangeRequest.changeRequestForAsset(asset).hidden = hidden
                    },
                    completionHandler = { success, _ ->
                        continuation.resume(if (success) HideResult.APPLIED else HideResult.FAILED)
                    },
                )
            }
        }
}
