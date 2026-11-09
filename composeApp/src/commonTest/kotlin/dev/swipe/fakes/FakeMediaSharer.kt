package dev.swipe.fakes

import dev.swipe.data.MediaSharer
import dev.swipe.domain.model.ShareResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [MediaSharer] for tests (§8.2, FR-32). Records every [share] id in order
 * and answers [result]. Set [gate] to hold the call suspended — the "media is still
 * loading" window — until the test completes it; a call cancelled while suspended is
 * counted in [cancelledCalls].
 */
class FakeMediaSharer : MediaSharer {
    /** Every [share] id, oldest first. */
    val shareCalls = mutableListOf<String>()

    /** Calls cancelled while suspended on [gate]. */
    var cancelledCalls = 0
        private set

    /** What [share] answers. */
    var result: ShareResult = ShareResult.PRESENTED

    /** When non-null, [share] suspends on it before answering. */
    var gate: CompletableDeferred<Unit>? = null

    /** When non-null, [share] throws this straight away instead of answering. */
    var throwOnShare: Throwable? = null

    override suspend fun share(id: String): ShareResult {
        shareCalls += id
        throwOnShare?.let { throw it }
        try {
            gate?.await()
        } catch (cancellation: CancellationException) {
            cancelledCalls++
            throw cancellation
        }
        return result
    }
}
