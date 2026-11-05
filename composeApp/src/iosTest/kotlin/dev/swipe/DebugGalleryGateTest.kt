package dev.swipe

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-6.4 debug-hook gate: the component gallery opens only for a debug build
 * launched with `-gallery`; a release build never reaches it, argument or
 * not. The framework build has no BuildConfig for Native targets, so
 * `iosApp/iosApp/iOSApp.swift` passes `debugBuild` from its own `#if DEBUG`
 * and this pure function carries the whole rule.
 */
class DebugGalleryGateTest {
    @Test
    fun `WP-6-4 debug build with the gallery argument opens the gallery`() {
        assertTrue(debugGalleryRequested(debugBuild = true, arguments = listOf("app", "-gallery")))
    }

    @Test
    fun `WP-6-4 debug build without the argument runs the app`() {
        assertFalse(debugGalleryRequested(debugBuild = true, arguments = listOf("app")))
    }

    @Test
    fun `WP-6-4 release build never opens the gallery`() {
        assertFalse(debugGalleryRequested(debugBuild = false, arguments = listOf("app", "-gallery")))
    }
}
