package dev.swipe.di

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.swipe.data.AudioSession
import dev.swipe.data.Haptics
import dev.swipe.data.MediaDeleter
import dev.swipe.data.MediaHider
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaLoader
import dev.swipe.data.MediaSharer
import dev.swipe.data.MediaSizing
import dev.swipe.data.PerfTrace
import dev.swipe.data.SettingsStore
import dev.swipe.data.SystemActions
import dev.swipe.data.VideoPreloader
import dev.swipe.data.image.BitmapLruCache
import dev.swipe.db.SwipeDatabase
import dev.swipe.platform.DatabaseDriverFactory
import dev.swipe.platform.IosAudioSession
import dev.swipe.platform.IosHaptics
import dev.swipe.platform.IosPerfTrace
import dev.swipe.platform.IosSystemActions
import dev.swipe.platform.NSUserDefaultsSettingsStore
import dev.swipe.platform.PhotoKitMediaHider
import dev.swipe.platform.PhotoKitMediaLibrary
import dev.swipe.platform.PhotoKitMediaLoader
import dev.swipe.platform.PhotoKitMediaSharer
import dev.swipe.platform.PhotoKitMediaSizing
import dev.swipe.platform.VideoItemCache
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
        // One PhotoKitMediaLibrary instance backs both typed views of the library
        // (WP-9.1, NFR-5): the read-side MediaLibrary everyone may hold, and the
        // write-side MediaDeleter the graph resolves only for CommitDeletionsUseCase.
        single { PhotoKitMediaLibrary() }
        single<MediaLibrary> { get<PhotoKitMediaLibrary>() }
        single<MediaDeleter> { get<PhotoKitMediaLibrary>() }
        single<SystemActions> { IosSystemActions() }
        single<SettingsStore> { NSUserDefaultsSettingsStore() }
        single<Haptics> { IosHaptics(settings = get()) }
        // Audio door (FR-16): createdAtStart applies the ambient category at launch,
        // before any video (including the queue viewer's) mounts.
        single<AudioSession>(createdAtStart = true) { IosAudioSession() }

        // Signpost tracing (WP-6.3, NFR-1, NFR-4): one shared object so the
        // cold-start span can begin at MainViewController before Koin resolves
        // anything, and the UI ends/closes spans through the graph.
        single<PerfTrace> { IosPerfTrace }

        // Image pipeline (WP-1.5): the decoded-bitmap cache is a singleton so all loaders
        // share the one NFR-3 budget of 5, and evicted bitmaps release their native pixels
        // on the way out (asSkiaBitmap().close() is the Compose MP release path).
        single { BitmapLruCache<ImageBitmap>(onEvict = { _, bitmap -> bitmap.asSkiaBitmap().close() }) }
        single<MediaLoader> { PhotoKitMediaLoader(settings = get(), cache = get()) }

        // Video pipeline (WP-4.1): the shared player-item cache doubles as the deck's
        // preloader (FR-16, §6.5 step 4); the `VideoPlayer` actual resolves the same
        // instance from the graph, so preloaded items mount warm.
        single { VideoItemCache(settings = get()) }
        single<VideoPreloader> { get<VideoItemCache>() }

        // Share sheet (WP-8.1, FR-32): exports originals on tap, network per FR-29.
        single<MediaSharer> { PhotoKitMediaSharer(settings = get()) }

        // Hide door (WP-8.11, FR-38): the only binding of MediaHider; the graph feeds
        // it to SwipeViewModel alone, and the §8.3 check-rules guard keeps it there.
        single<MediaHider> { PhotoKitMediaHider() }

        // Storage sizing (WP-8.4, FR-34): read-only estimate for the delete
        // confirmation line. Unlike the write doors it needs no guard — it can
        // only read — but it stays Koin-bound like every other platform bridge.
        single<MediaSizing> { PhotoKitMediaSizing() }
    }
