package dev.swipe.platform

import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/** The end of a presentation chain: follows [next] from [root] until it returns
 * null and returns the last non-null value. Pure — no UIKit — so the walk is testable. */
internal fun <T : Any> topOfChain(
    root: T,
    next: (T) -> T?,
): T {
    var top = root
    while (true) {
        top = next(top) ?: return top
    }
}

/**
 * The view controller to present on for the FR-2 limited-library picker and the
 * FR-32 share sheet: the key window's root in the foreground window scene (falls
 * back to any connected scene, then any window), followed down its
 * `presentedViewController` chain so the presenter is whatever is on top.
 * Null when there's no window.
 */
internal fun topViewController(): UIViewController? {
    val scenes = UIApplication.sharedApplication().connectedScenes.filterIsInstance<UIWindowScene>()
    val scene =
        scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
            ?: scenes.firstOrNull()
    val windows = scene?.windows?.filterIsInstance<UIWindow>() ?: emptyList()
    val window = windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
    val root = window?.rootViewController ?: return null
    return topOfChain(root) { it.presentedViewController }
}
