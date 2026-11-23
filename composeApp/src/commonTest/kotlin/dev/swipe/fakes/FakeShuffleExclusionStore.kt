package dev.swipe.fakes

import dev.swipe.data.ShuffleExclusionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [ShuffleExclusionStore] for tests (§8.2, FR-37). Starts with
 * [excluded] ids; [excludedAlbumIdsFlow] behaves like the SQLDelight Flow —
 * current value on subscribe, then every write. [failNextSetExcluded] throws
 * once without touching the state, to prove a failed write leaves the switch
 * where it was (NFR-7).
 */
class FakeShuffleExclusionStore(
    excluded: Set<String> = emptySet(),
) : ShuffleExclusionStore {
    private val state = MutableStateFlow(excluded)

    val setExcludedCalls = mutableListOf<Pair<String, Boolean>>()
    var failNextSetExcluded: Throwable? = null

    /** Make the set change without recording a call — models another writer. */
    fun setExcludedIds(ids: Set<String>) {
        state.value = ids
    }

    override suspend fun excludedAlbumIds(): Set<String> = state.value

    override fun excludedAlbumIdsFlow(): Flow<Set<String>> = state

    override suspend fun setExcluded(
        albumId: String,
        excluded: Boolean,
    ) {
        failNextSetExcluded?.let {
            failNextSetExcluded = null
            throw it
        }
        setExcludedCalls += albumId to excluded
        state.value =
            if (excluded) {
                state.value + albumId
            } else {
                state.value - albumId
            }
    }
}
