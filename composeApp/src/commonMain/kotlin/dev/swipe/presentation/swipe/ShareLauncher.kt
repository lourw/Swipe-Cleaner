package dev.swipe.presentation.swipe

import dev.swipe.data.MediaSharer
import dev.swipe.domain.model.ShareResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The single-flight share of [SwipeViewModel] (FR-32): one [MediaSharer.share] at a
 * time, its loading flag reported through [onLoading], a failed load through
 * [onFailed]. It knows nothing about decisions or the deck index — it cannot touch
 * them — and the ViewModel calls [cancel] on every deck move so a sheet still
 * loading for a card that has left never opens.
 */
internal class ShareLauncher(
    private val scope: CoroutineScope,
    private val sharer: MediaSharer,
    private val onLoading: (Boolean) -> Unit,
    private val onFailed: () -> Unit,
) {
    private var job: Job? = null

    /** Share [id]; ignored while an earlier share is still loading. */
    fun launch(id: String) {
        if (job?.isActive == true) return
        onLoading(true)
        job =
            scope.launch {
                try {
                    val result =
                        try {
                            sharer.share(id)
                        } catch (cancellation: CancellationException) {
                            throw cancellation // a deck move or teardown, not a failure
                        } catch (_: Exception) {
                            ShareResult.FAILED
                        }
                    if (result == ShareResult.FAILED) onFailed()
                } finally {
                    // A cancelled job must not lower the flag of a newer share.
                    val self = coroutineContext[Job]
                    if (job === self) job = null
                    if (job == null) onLoading(false)
                }
            }
    }

    /** Abandon a share that is still loading (a deck move, FR-32). */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
