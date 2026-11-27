package dev.swipe.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.swipe.data.ShuffleExclusionStore
import dev.swipe.db.SwipeDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * SQLDelight-backed [ShuffleExclusionStore] over the `shuffle_exclusion` table
 * (FR-37, §7). [excludedAlbumIdsFlow] uses `asFlow().mapToList()` so Home and the
 * Albums switches update live, the same pattern as
 * [SqlDecisionStore.deleteQueue]. Every query runs on [dispatcher]; writes
 * rethrow driver failures so the caller's state stays where it was (NFR-7). The
 * database arrives as a [Deferred]: a store built before it opens suspends until
 * it does (NFR-4, §7).
 */
class SqlShuffleExclusionStore(
    private val database: Deferred<SwipeDatabase>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ShuffleExclusionStore {
    constructor(
        database: SwipeDatabase,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(CompletableDeferred(database), dispatcher)

    override suspend fun excludedAlbumIds(): Set<String> =
        withContext(dispatcher) {
            database
                .await()
                .shuffleExclusionQueries
                .allIds()
                .executeAsList()
                .toSet()
        }

    override fun excludedAlbumIdsFlow(): Flow<Set<String>> =
        flow {
            emitAll(
                database
                    .await()
                    .shuffleExclusionQueries
                    .allIds()
                    .asFlow()
                    .mapToList(dispatcher),
            )
        }.map { ids -> ids.toSet() }

    override suspend fun setExcluded(
        albumId: String,
        excluded: Boolean,
    ) {
        withContext(dispatcher) {
            val exclusions = database.await().shuffleExclusionQueries
            if (excluded) exclusions.exclude(albumId) else exclusions.include(albumId)
        }
    }
}
