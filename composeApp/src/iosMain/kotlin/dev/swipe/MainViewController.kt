package dev.swipe

import androidx.compose.ui.window.ComposeUIViewController
import dev.swipe.di.commonModule
import dev.swipe.di.iosModule
import org.koin.core.context.startKoin
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

private var koinStarted = false

/**
 * Entry point called from `iosApp/iosApp/iOSApp.swift`.
 *
 * Launch with `-gallery` (for example
 * `xcrun simctl launch booted dev.swipe.cleaner -gallery`) to open the debug
 * WP-1.2 component gallery instead of the app's permission flow.
 */
@Suppress("FunctionName", "ktlint:standard:function-naming") // Swift calls this like a type initialiser.
fun MainViewController(): UIViewController {
    if (!koinStarted) {
        startKoin { modules(commonModule, iosModule) }
        koinStarted = true
    }
    val gallery = NSProcessInfo.processInfo.arguments.contains("-gallery")
    return ComposeUIViewController { App(gallery = gallery) }
}
