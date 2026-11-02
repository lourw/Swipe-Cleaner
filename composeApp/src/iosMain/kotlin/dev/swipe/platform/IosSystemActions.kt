@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.SystemActions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.presentLimitedLibraryPickerFromViewController
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import kotlin.time.Instant

/**
 * iOS implementations of the system actions (WP-1.4; §6.4, FR-2, FR-3, FR-31).
 * Everything here is a thin bridge — no state beyond one notification observer
 * inside [isReduceMotionEnabled]'s flow.
 */
class IosSystemActions : SystemActions {
    /** Opens this app's page in Settings via `UIApplicationOpenSettingsURLString` (FR-3). */
    override fun openSettings() {
        val settingsUrl = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication().openURL(settingsUrl, emptyMap<Any?, Any?>(), null)
    }

    /**
     * Presents the limited-library picker so the user can add items (FR-2). It must
     * run on the foreground window's root view controller; with no window (background)
     * or a permission that doesn't allow the picker, PhotoKit no-ops — a log is enough.
     */
    override fun presentLimitedLibraryPicker() {
        val controller = frontmostViewController()
        if (controller == null) {
            println("SwipeCleaner: no foreground view controller for the limited library picker")
            return
        }
        PHPhotoLibrary.sharedPhotoLibrary().presentLimitedLibraryPickerFromViewController(controller)
    }

    /**
     * Current Reduce Motion state, re-emitted whenever the accessibility setting
     * changes (§5.6, NFR-8). The observer is removed when the collector goes away.
     */
    override fun isReduceMotionEnabled(): Flow<Boolean> =
        callbackFlow {
            trySend(UIAccessibilityIsReduceMotionEnabled())
            val center = NSNotificationCenter.defaultCenter
            val observer =
                center.addObserverForName(
                    name = UIAccessibilityReduceMotionStatusDidChangeNotification,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                    usingBlock = { _ -> trySend(UIAccessibilityIsReduceMotionEnabled()) },
                )
            awaitClose { center.removeObserver(observer) }
        }

    /** `"1.2 (345)"` — `CFBundleShortVersionString` plus build (FR-31). */
    override fun appVersion(): String {
        val bundle = NSBundle.mainBundle
        val shortVersion = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"
        val build = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "?"
        return "$shortVersion ($build)"
    }

    /** Reading the provisioning profile lands in WP-6.1; null hides the row (FR-31). */
    override fun signingExpiry(): Instant? = null

    /**
     * The root view controller of the key window in the active window scene (falls
     * back to any connected scene, then any window) — what PhotoKit wants for the
     * limited-library picker.
     */
    private fun frontmostViewController(): UIViewController? {
        val scenes = UIApplication.sharedApplication().connectedScenes.filterIsInstance<UIWindowScene>()
        val scene =
            scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
                ?: scenes.firstOrNull()
        val windows = scene?.windows?.filterIsInstance<UIWindow>() ?: emptyList()
        val window = windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
        return window?.rootViewController
    }
}
