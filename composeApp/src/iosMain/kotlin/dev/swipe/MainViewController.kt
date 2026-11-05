package dev.swipe

import androidx.compose.ui.window.ComposeUIViewController
import dev.swipe.data.PerfSpan
import dev.swipe.di.commonModule
import dev.swipe.di.iosModule
import dev.swipe.platform.IosPerfTrace
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
    // NFR-4 cold-start span: this is the earliest Kotlin runs, a floor for
    // the real process spawn (dyld + Swift runtime start before it). It ends
    // at Home's first composition — see HomeDestination. docs/perf.md.
    IosPerfTrace.begin(PerfSpan.COLD_START_HOME)
    if (!koinStarted) {
        startKoin { modules(commonModule, iosModule) }
        koinStarted = true
    }
    val gallery = NSProcessInfo.processInfo.arguments.contains("-gallery")
    return ComposeUIViewController { App(gallery = gallery) }
}
