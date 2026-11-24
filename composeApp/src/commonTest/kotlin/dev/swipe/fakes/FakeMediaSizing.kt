package dev.swipe.fakes

import dev.swipe.data.MediaSizing
import dev.swipe.domain.model.StorageEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [MediaSizing] for tests (§8.2, FR-34). Records every [estimateBytes]
 * id list in order and answers [estimate]. Set [gate] to hold a call suspended —
 * the "estimate still loading" window — until the test completes it; a call
 * cancelled while suspended is counted in [cancelledCalls].
 */
class FakeMediaSizing : MediaSizing {
    /** Every [estimateBytes] id list, oldest first. */
    val estimateCalls = mutableListOf<List<String>>()

    /** Calls cancelled while suspended on [gate]. */
    var cancelledCalls = 0
        private set

    /** What [estimateBytes] answers. */
    var estimate: StorageEstimate = StorageEstimate.Complete(totalBytes = 0L)

    /** When non-null, [estimateBytes] suspends on it before answering. */
    var gate: CompletableDeferred<Unit>? = null

    /** When non-null, [estimateBytes] throws this straight away instead of answering. */
    var throwOnEstimate: Throwable? = null

    override suspend fun estimateBytes(ids: List<String>): StorageEstimate {
        estimateCalls += ids
        throwOnEstimate?.let { throw it }
        try {
            gate?.await()
        } catch (cancellation: CancellationException) {
            cancelledCalls++
            throw cancellation
        }
        return estimate
    }
}
