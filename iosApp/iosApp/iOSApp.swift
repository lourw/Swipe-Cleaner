import ComposeApp
import SwiftUI

@main
struct SwipeCleanerApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

/// Hosts the shared Compose UI (`composeApp/src/iosMain/kotlin/dev/swipe/MainViewController.kt`).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // WP-6.4: the `-gallery` launch argument only opens the debug
        // component gallery while this (Xcode Debug configuration) flag is
        // set; release builds can never reach it.
        #if DEBUG
        return MainViewControllerKt.MainViewController(debugBuild: true)
        #else
        // Kotlin default parameters are not exported to the Obj-C header, so
        // the Release configuration must pass `debugBuild` explicitly — the
        // parameterless overload does not exist from Swift.
        return MainViewControllerKt.MainViewController(debugBuild: false)
        #endif
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
