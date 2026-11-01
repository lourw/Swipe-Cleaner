package dev.swipe

import androidx.compose.ui.window.ComposeUIViewController
import dev.swipe.di.commonModule
import dev.swipe.di.iosModule
import org.koin.core.context.startKoin
import platform.UIKit.UIViewController

private var koinStarted = false

/** Entry point called from `iosApp/iosApp/iOSApp.swift`. */
@Suppress("FunctionName", "ktlint:standard:function-naming") // Swift calls this like a type initialiser.
fun MainViewController(): UIViewController {
    if (!koinStarted) {
        startKoin { modules(commonModule, iosModule) }
        koinStarted = true
    }
    return ComposeUIViewController { App() }
}
