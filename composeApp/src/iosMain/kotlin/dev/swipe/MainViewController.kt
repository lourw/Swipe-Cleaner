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
 * Entry point called from `iosApp/iosApp/iOSApp.swift`, which passes
 * [debugBuild] from its own `#if DEBUG` — the only way the framework build
 * tells release apart (KGP 2.4 has no BuildConfig for Native targets).
 *
 * The debug WP-1.2 component gallery opens only when [debugBuild] is true
 * *and* the process was launched with `-gallery` (for example
 * `xcrun simctl launch booted dev.swipe.cleaner -gallery`). Release builds
 * never reach the gallery, launch argument or not (WP-6.4).
 */
@Suppress("FunctionName", "ktlint:standard:function-naming") // Swift calls this like a type initialiser.
fun MainViewController(debugBuild: Boolean): UIViewController {
    // NFR-4 cold-start span: this is the earliest Kotlin runs, a floor for
    // the real process spawn (dyld + Swift runtime start before it). It ends
    // at Home's first composition — see HomeDestination. docs/perf.md.
    IosPerfTrace.begin(PerfSpan.COLD_START_HOME)
    if (!koinStarted) {
        startKoin { modules(commonModule, iosModule) }
        koinStarted = true
    }
    val gallery =
        debugGalleryRequested(
            debugBuild = debugBuild,
            arguments = NSProcessInfo.processInfo.arguments.filterIsInstance<String>(),
        )
    return ComposeUIViewController { App(gallery = gallery) }
}

/**
 * The debug-gallery gate (WP-6.4): debug build *and* the `-gallery` launch
 * argument. Split out from [MainViewController] so the release guard is a
 * plain function the tests can pin.
 */
internal fun debugGalleryRequested(
    debugBuild: Boolean,
    arguments: List<String>,
): Boolean = debugBuild && arguments.contains("-gallery")
