@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.platform

import dev.swipe.data.SystemActions
import dev.swipe.data.parseProvisioningProfileExpiration
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
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
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind
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

    /**
     * Free-provisioning expiry for the Settings row (FR-31, WP-6.1): read
     * `embedded.mobileprovision` from the app bundle and parse its plist. Every
     * failure — no profile (TestFlight, App Store), unreadable bytes, missing or
     * malformed `ExpirationDate` — returns null and the row is hidden. The file
     * is a CMS-signed blob, so the bytes are decoded as Latin-1 (every byte
     * maps, the plist inside is ASCII) before the pure parser runs.
     */
    override fun signingExpiry(): Instant? =
        NSBundle.mainBundle
            .pathForResource("embedded", "mobileprovision")
            ?.let(::readFileAsLatin1)
            ?.let(::parseProvisioningProfileExpiration)

    /**
     * Whole-file read via POSIX stdio (the NSData convenience initializers are
     * hidden deprecations in the current SDK). Bytes become Chars 1:1 (Latin-1)
     * for the parser: every byte maps, and the plist inside the CMS blob is ASCII.
     */
    private fun readFileAsLatin1(path: String): String? =
        readWholeFile(path)?.let { bytes ->
            CharArray(bytes.size) { i -> bytes[i].toUByte().toInt().toChar() }.concatToString()
        }

    /** The whole file, or null when it can't be opened, is empty, or is implausibly large. */
    private fun readWholeFile(path: String): ByteArray? =
        memScoped {
            val file = fopen(path, "rb")
            try {
                file?.let(::readCapped)
            } finally {
                file?.let(::fclose)
            }
        }

    /** One `fread` of the whole handle — a profile is a few tens of KB. */
    private fun readCapped(file: CPointer<FILE>): ByteArray? {
        fseek(file, 0L, SEEK_END)
        val size = ftell(file)
        if (size <= 0L || size > MAX_PROFILE_BYTES) {
            return null
        }
        rewind(file)
        val buffer = ByteArray(size.toInt())
        val read =
            buffer.usePinned { pinned ->
                fread(pinned.addressOf(0), sizeOf<ByteVar>().convert(), size.convert(), file)
            }
        return if (read.toLong() == size) buffer else null
    }

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

    private companion object {
        /** Safety cap for the profile read; real profiles are a few tens of KB. */
        private const val MAX_PROFILE_BYTES = 1L shl 20
    }
}
