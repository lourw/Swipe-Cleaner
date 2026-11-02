package dev.swipe.fakes

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import dev.swipe.data.LoadedImage
import dev.swipe.data.MediaLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * In-memory [MediaLoader] for tests (§8.2). [image] emits [degradedBitmap] first and
 * [fullBitmap] second, mirroring the real degraded-then-full sequence (NFR-1) so later
 * ViewModel and UI tests can rely on it. Records every call; `failNext…` switches throw once.
 */
class FakeMediaLoader : MediaLoader {
    /** Emitted first by [image], before the full-quality bitmap (NFR-1). */
    var degradedBitmap: ImageBitmap = ImageBitmap(width = 1, height = 1)

    /** Emitted second by [image] (NFR-1). */
    var fullBitmap: ImageBitmap = ImageBitmap(width = 2, height = 2)

    /** Value for [LoadedImage.isCloud] on both emissions; set it to exercise the badge (R6). */
    var isCloud: Boolean = false

    /** Every [image] call as (id, targetPx), oldest first. */
    val imageRequests = mutableListOf<Pair<String, IntSize>>()

    /** Every [prefetch] call, oldest first. */
    val prefetchCalls = mutableListOf<List<String>>()

    /** Every [cancelPrefetch] call, oldest first. */
    val cancelPrefetchCalls = mutableListOf<List<String>>()

    /** When non-null, the next collected [image] flow throws this once and the switch resets. */
    var failNextImage: Throwable? = null

    override fun image(
        id: String,
        targetPx: IntSize,
    ): Flow<LoadedImage> {
        imageRequests += id to targetPx
        return flow {
            failNextImage?.let {
                failNextImage = null
                throw it
            }
            emit(LoadedImage(degradedBitmap, isDegraded = true, isCloud = isCloud))
            emit(LoadedImage(fullBitmap, isDegraded = false, isCloud = isCloud))
        }
    }

    override fun prefetch(
        ids: List<String>,
        targetPx: IntSize,
    ) {
        prefetchCalls += ids
    }

    override fun cancelPrefetch(
        ids: List<String>,
        targetPx: IntSize,
    ) {
        cancelPrefetchCalls += ids
    }
}
