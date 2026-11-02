package dev.swipe.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.IntSize
import dev.swipe.data.LoadedImage
import dev.swipe.data.MediaLoader

/**
 * Collects [MediaLoader.image] for one card (WP-1.9, NFR-1): the fast degraded bitmap
 * arrives first and the sharp one replaces it (the loader closes the flow after the
 * full-quality result, §6.4). Returns null until the first emission.
 *
 * `produceState` keys the collector on `(id, targetPx, loader)`, so recompositions of
 * the same card reuse the one collector — never a new `image()` collection per frame
 * (WP-1.5 carry-over) — and leaving the composition cancels it, which cancels the
 * underlying `PHImageRequestID` (§6.8). Pass [MediaLoader.FULL_TARGET_PX] for a
 * full-screen card so the request shares the prefetch's cache key (NFR-1, NFR-3).
 */
@Composable
fun rememberMediaImage(
    id: String,
    targetPx: IntSize,
    loader: MediaLoader,
): LoadedImage? =
    produceState(null as LoadedImage?, id, targetPx, loader) {
        loader
            .image(id, targetPx)
            .collect { loaded -> value = loaded }
    }.value
