package dev.swipe.di

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaLoader
import dev.swipe.data.SettingsStore
import dev.swipe.data.SystemActions
import dev.swipe.data.image.BitmapLruCache
import dev.swipe.db.SwipeDatabase
import dev.swipe.platform.DatabaseDriverFactory
import dev.swipe.platform.IosSystemActions
import dev.swipe.platform.NSUserDefaultsSettingsStore
import dev.swipe.platform.PhotoKitMediaLibrary
import dev.swipe.platform.PhotoKitMediaLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS implementations of the `data/` interfaces (REQUIREMENTS §6.1). The driver opens
 * eagerly when Koin starts (§7, FR-20), but on [Dispatchers.Default]: opening runs
 * file IO (create file, backup exclusion, warm-up query, corrupt-DB recovery), and
 * that must not delay the first frame inside `MainViewController()` (NFR-4). The
 * database file materialises a moment after launch, and [SwipeDatabase] waits for it
 * only if something resolves it before the warm-up finishes.
 */
val iosModule: Module =
    module {
        single(createdAtStart = true) {
            CoroutineScope(Dispatchers.Default + SupervisorJob())
                .async { DatabaseDriverFactory().create() }
        }
        single { SwipeDatabase(runBlocking { get<Deferred<NativeSqliteDriver>>().await() }) }

        // Platform bridges (WP-1.4): PhotoKit reads, system actions, and settings.
        single<MediaLibrary> { PhotoKitMediaLibrary() }
        single<SystemActions> { IosSystemActions() }
        single<SettingsStore> { NSUserDefaultsSettingsStore() }

        // Image pipeline (WP-1.5): the decoded-bitmap cache is a singleton so all loaders
        // share the one NFR-3 budget of 5, and evicted bitmaps release their native pixels
        // on the way out (asSkiaBitmap().close() is the Compose MP release path).
        single { BitmapLruCache<ImageBitmap>(onEvict = { _, bitmap -> bitmap.asSkiaBitmap().close() }) }
        single<MediaLoader> { PhotoKitMediaLoader(settings = get(), cache = get()) }
    }
