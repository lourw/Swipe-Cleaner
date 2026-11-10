@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.MediaSharer
import dev.swipe.data.SettingsStore
import dev.swipe.data.photoKitShareResourceTypes
import dev.swipe.domain.model.ShareResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import kotlin.coroutines.resume

/**
 * iOS [MediaSharer] (FR-32): exports the item's original resources with
 * `PHAssetResourceManager.writeDataForAssetResource` into a fresh temp directory and
 * presents `UIActivityViewController` over their file URLs.
 *
 * **What is shared.** [photoKitShareResourceTypes] picks the resources: the photo,
 * the video, or a Live Photo's photo plus paired video. File URLs keep the original
 * filenames and formats (HEIC, MOV, …), so a video goes out as a video and a Live
 * Photo pair as a Live Photo — never a rendered still frame.
 *
 * **When.** Only on [share] — nothing is exported ahead of the tap. iCloud-only
 * originals download with `networkAccessAllowed = SettingsStore.includeCloudOnly`,
 * the same rule as every other load (FR-29, NFR-6); with the setting off such an
 * item fails and the caller shows its toast.
 *
 * **Temp files.** Each share writes into its own `swipe-share/<uuid>/` directory,
 * deleted when the sheet reports it is done (dismissed, or an activity completed).
 * A failed or cancelled export deletes it straight away. As a backstop for a sheet
 * whose completion never fires, each new share first clears the whole
 * `swipe-share/` directory — a new share can only start once the previous sheet is
 * gone, since the sheet is modal.
 *
 * **Threading.** The asset fetch and the exports run on [ioDispatcher]; the sheet is
 * presented on the main thread.
 */
class PhotoKitMediaSharer(
    private val settings: SettingsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaSharer {
    override suspend fun share(id: String): ShareResult {
        val directory = withContext(ioDispatcher) { prepareDirectory() } ?: return ShareResult.FAILED
        var presented = false
        try {
            val files = withContext(ioDispatcher) { export(id, directory) }
            if (files != null) withContext(Dispatchers.Main) { presented = present(files, directory) }
        } finally {
            // A failed or cancelled export never reaches the sheet: drop its files
            // now. Once the sheet is up, its completion handler owns them.
            if (!presented) removeDirectory(directory)
        }
        return if (presented) ShareResult.PRESENTED else ShareResult.FAILED
    }

    /** Clears earlier share leftovers and creates this share's own directory. */
    private fun prepareDirectory(): String? {
        val manager = NSFileManager.defaultManager
        manager.removeItemAtPath(shareRoot(), null)
        val directory = shareRoot() + "/" + NSUUID().UUIDString
        val created = manager.createDirectoryAtPath(directory, true, null, null)
        return if (created) directory else null
    }

    /** The chosen resources written under [directory]; null when any of them fails. */
    private suspend fun export(
        id: String,
        directory: String,
    ): List<NSURL>? {
        val resources = shareResources(id)
        val networkAllowed = settings.includeCloudOnly.value
        val urls = mutableListOf<NSURL>()
        val usedNames = mutableSetOf<String>()
        for ((index, resource) in resources.withIndex()) {
            // All resources go directly into [directory] under their original
            // filenames: receivers pair a Live Photo's photo and video halves by
            // same-directory + same-basename proximity, so the halves must never be
            // split into per-resource subfolders. In the unlikely case two
            // resources report the same filename, the later one gets an index
            // prefix so neither write clobbers the other.
            val filename = resource.originalFilename
            val name = if (usedNames.add(filename)) filename else "$index-$filename"
            urls += writeInto(directory, name, resource, networkAllowed) ?: break
        }
        return urls.takeIf { it.isNotEmpty() && it.size == resources.size }
    }

    /** [id]'s resources to share, in order; empty when the asset or its resources are gone. */
    private fun shareResources(id: String): List<PHAssetResource> {
        val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null).firstObject as? PHAsset ?: return emptyList()
        val resources = PHAssetResource.assetResourcesForAsset(asset).filterIsInstance<PHAssetResource>()
        return photoKitShareResourceTypes(
            mediaTypeId = asset.mediaType.toInt(),
            subtypes = asset.mediaSubtypes.toInt(),
            availableTypes = resources.map { it.type.toInt() },
        ).mapNotNull { type -> resources.firstOrNull { it.type.toInt() == type } }
    }

    /** [resource] written into [directory] as [filename]; null on failure. */
    private suspend fun writeInto(
        directory: String,
        filename: String,
        resource: PHAssetResource,
        networkAllowed: Boolean,
    ): NSURL? {
        val url = NSURL.fileURLWithPath("$directory/$filename")
        return url.takeIf { write(resource, url, networkAllowed) }
    }

    /**
     * One `writeDataForAssetResource` call. It has no cancel handle: a cancelled
     * caller stops waiting, and [share] deletes the directory the write lands in.
     */
    private suspend fun write(
        resource: PHAssetResource,
        url: NSURL,
        networkAllowed: Boolean,
    ): Boolean {
        val options = PHAssetResourceRequestOptions().apply { networkAccessAllowed = networkAllowed }
        return suspendCancellableCoroutine { cont ->
            PHAssetResourceManager
                .defaultManager()
                .writeDataForAssetResource(resource, url, options) { error ->
                    if (cont.isActive) cont.resume(error == null)
                }
        }
    }

    /** Presents the share sheet over the topmost view controller; false with no window. */
    private fun present(
        files: List<NSURL>,
        directory: String,
    ): Boolean {
        val host = topViewController() ?: return false
        val controller = UIActivityViewController(activityItems = files, applicationActivities = null)
        controller.completionWithItemsHandler = { activityType, completed, _, _ ->
            // Null activity: the sheet was dismissed. A completed activity also ends
            // the sheet. A cancelled sub-activity can return to the sheet, so its
            // files stay until the final callback (or the next share's sweep).
            if (activityType == null || completed) removeDirectory(directory)
        }
        host.presentViewController(controller, animated = true, completion = null)
        return true
    }

    private fun removeDirectory(directory: String) {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
    }

    private fun shareRoot(): String = NSTemporaryDirectory() + SHARE_DIRECTORY

    /**
     * The foreground window's root view controller, walked up its presented chain,
     * so the sheet lands on top of whatever is showing.
     */
    private fun topViewController(): UIViewController? {
        val scenes = UIApplication.sharedApplication().connectedScenes.filterIsInstance<UIWindowScene>()
        val scene =
            scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
                ?: scenes.firstOrNull()
        val windows = scene?.windows?.filterIsInstance<UIWindow>() ?: emptyList()
        val window = windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
        var top = window?.rootViewController ?: return null
        while (true) {
            top = top.presentedViewController ?: return top
        }
    }

    private companion object {
        /** Temp subdirectory holding the files of the share sheet that is up (FR-32). */
        const val SHARE_DIRECTORY = "swipe-share"
    }
}
