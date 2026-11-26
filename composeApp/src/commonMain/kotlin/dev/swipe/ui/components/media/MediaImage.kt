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
 *
 * FR-38 (§5.6 Hide advance): `produceState` keeps its value across a key change, so
 * the produced value carries the id it was loaded for ([IdImage]) and the result is
 * exposed only while that id still equals [id]. When a card is promoted and the id
 * changes to one whose flow has not emitted yet, this returns null — never the
 * previous (hidden) card's bitmap.
 */
@Composable
fun rememberMediaImage(
    id: String,
    targetPx: IntSize,
    loader: MediaLoader,
): LoadedImage? {
    val state =
        produceState(null as IdImage?, id, targetPx, loader) {
            loader
                .image(id, targetPx)
                .collect { loaded -> value = IdImage(id, loaded) }
        }
    return state.value.takeIf { it?.id == id }?.image
}

/** A [LoadedImage] tagged with the card id it was decoded for (FR-38). */
private class IdImage(
    val id: String,
    val image: LoadedImage,
)

/**
 * The image a top card draws (FR-38, §5.6 Hide advance): its own load if it has one,
 * otherwise the image the under card already drew for the very same id. When a card is
 * promoted to the top its own [rememberMediaImage] collector restarts for the id, so
 * without this the frame would go blank until the decode re-arrives — but the under
 * card already held the pixels for exactly this id. The borrow is gated on the id so
 * an image is only ever drawn for the card it was loaded for (AC3).
 */
internal fun promotedImage(
    cardId: String,
    own: LoadedImage?,
    underId: String?,
    underImage: LoadedImage?,
): LoadedImage? =
    when {
        own != null -> own
        underId == cardId -> underImage
        else -> null
    }
