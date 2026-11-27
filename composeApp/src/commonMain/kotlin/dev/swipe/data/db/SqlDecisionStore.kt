package dev.swipe.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.swipe.data.DecisionStore
import dev.swipe.db.SwipeDatabase
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.SessionCounts
import dev.swipe.domain.model.Stats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * SQLDelight-backed [DecisionStore] over the `decision` / `session_skip` tables
 * (REQUIREMENTS §7, WP-1.3). Every query runs on [dispatcher]; writes rethrow driver
 * failures so the caller can revert the card (NFR-7). [deleteQueue] and [stats] use
 * `asFlow().mapToList()` so the badge and Home update live (FR-23, FR-10). The
 * database arrives as a [Deferred]: a store built before it opens suspends until it
 * does, so resolving or using a store never blocks the calling thread (NFR-4, §7).
 */
class SqlDecisionStore(
    private val database: Deferred<SwipeDatabase>,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DecisionStore {
    constructor(
        database: SwipeDatabase,
        clock: Clock,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(CompletableDeferred(database), clock, dispatcher)

    override suspend fun record(
        id: String,
        decision: Decision,
        sessionId: String,
    ) {
        withContext(dispatcher) {
            val db = database.await()
            if (decision == Decision.SKIP) {
                // SKIP goes to session_skip only, never into decision (§7 comment, FR-14).
                db.sessionQueries.insertSkip(sessionId, id)
            } else {
                db.decisionQueries.upsert(id, decision.name, clock.now().toEpochMilliseconds(), sessionId)
            }
        }
    }

    override suspend fun remove(
        id: String,
        sessionId: String,
    ) {
        withContext(dispatcher) {
            // Undo clears whichever table holds the row (FR-15); one transaction so a
            // half-applied undo can't leave a ghost behind. The skip side is scoped to
            // [sessionId] so another session's skip of the same asset survives (§6.5 step 1).
            val db = database.await()
            db.transaction {
                db.decisionQueries.deleteById(id)
                db.sessionQueries.deleteSkipsForSession(sessionId, id)
            }
        }
    }

    override suspend fun decidedIds(): Set<String> =
        withContext(dispatcher) {
            database
                .await()
                .decisionQueries
                .decidedIds()
                .executeAsList()
                .toSet()
        }

    override suspend fun sessionCounts(sessionId: String): SessionCounts =
        withContext(dispatcher) {
            // COALESCE in the query keeps the SUMs non-null, so the generated row is Long.
            database
                .await()
                .decisionQueries
                .sessionCounts(sessionId)
                .executeAsOne()
                .let { row ->
                    SessionCounts(
                        kept = row.kept.toInt(),
                        queued = row.queued.toInt(),
                        reviewed = row.reviewed.toInt(),
                    )
                }
        }

    override fun deleteQueue(): Flow<List<String>> =
        flow {
            emitAll(
                database
                    .await()
                    .decisionQueries
                    .deleteQueue()
                    .asFlow()
                    .mapToList(dispatcher),
            )
        }

    override suspend fun markDeleted(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(dispatcher) {
            database.await().decisionQueries.markDeleted(ids)
        }
    }

    override suspend fun dropFromQueue(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(dispatcher) {
            // WHERE decision = 'DELETE' (Decision.sq): only queued rows drop, a
            // KEEP or DELETED row for the same id survives (FR-28, WP-7.6).
            database.await().decisionQueries.dropFromQueue(ids)
        }
    }

    override suspend fun clearKeeps() {
        withContext(dispatcher) {
            database.await().decisionQueries.clearKeeps()
        }
    }

    override fun stats(): Flow<Stats> =
        flow {
            emitAll(
                database
                    .await()
                    .decisionQueries
                    .countByDecision()
                    .asFlow()
                    .mapToList(dispatcher),
            )
        }.map { rows ->
            // reviewed / kept / deleted / queued all come from these COUNT rows (FR-10, §7).
            var reviewed = 0
            var kept = 0
            var deleted = 0
            var queued = 0
            for (row in rows) {
                val total = row.total.toInt()
                reviewed += total
                when (row.decision) {
                    Decision.KEEP.name -> kept = total
                    Decision.DELETE.name -> queued = total
                    Decision.DELETED.name -> deleted = total
                    // SKIP rows never exist in decision (§7), so nothing else to count.
                }
            }
            Stats(reviewed = reviewed, kept = kept, deleted = deleted, queued = queued)
        }
}
