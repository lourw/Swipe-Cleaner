package dev.swipe.data.db

import dev.swipe.data.SessionStore
import dev.swipe.db.SwipeDatabase
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SessionRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * SQLDelight-backed [SessionStore] over the `session` / `session_skip` tables
 * (REQUIREMENTS §7, WP-1.3). [DeckSource] is stored as JSON in `session.source` (§7);
 * timestamps come from the injected [Clock]. Every query runs on [dispatcher], and
 * writes rethrow driver failures (NFR-7). The database arrives as a [Deferred]: a
 * store built before it opens suspends until it does (NFR-4, §7).
 */
class SqlSessionStore(
    private val database: Deferred<SwipeDatabase>,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val json: Json = Json,
) : SessionStore {
    constructor(
        database: SwipeDatabase,
        clock: Clock,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        json: Json = Json,
    ) : this(CompletableDeferred(database), clock, dispatcher, json)

    override suspend fun create(
        source: DeckSource,
        typeFilter: MediaTypeFilter,
        seed: Long,
    ): String =
        withContext(dispatcher) {
            val sessions = database.await().sessionQueries
            val id = Uuid.random().toString()
            sessions.insert(
                id = id,
                source = json.encodeToString(DeckSource.serializer(), source),
                type_filter = typeFilter.name,
                seed = seed,
                started_at = clock.now().toEpochMilliseconds(),
            )
            id
        }

    override suspend fun end(id: String) {
        withContext(dispatcher) {
            database.await().sessionQueries.endSession(clock.now().toEpochMilliseconds(), id)
        }
    }

    override suspend fun endOtherOpenSessions(exceptId: String) {
        withContext(dispatcher) {
            database.await().sessionQueries.endOtherOpenSessions(clock.now().toEpochMilliseconds(), exceptId)
        }
    }

    /**
     * The most recent open session, or null when there is none. An undecodable
     * row — a `source` JSON or `type_filter` from a build this version cannot
     * read — is *ended* on the spot and reported as no open session, so one
     * corrupt row never blocks every future deck (WP-8.10 prerequisite): the
     * next call sees a clean table instead of throwing on the same row forever.
     */
    override suspend fun openSession(): SessionRecord? =
        withContext(dispatcher) {
            val sessions = database.await().sessionQueries
            val row = sessions.selectOpen().executeAsOneOrNull() ?: return@withContext null
            val record =
                try {
                    SessionRecord(
                        id = row.id,
                        source = json.decodeFromString(DeckSource.serializer(), row.source),
                        typeFilter = MediaTypeFilter.valueOf(row.type_filter),
                        seed = row.seed,
                        startedAt = Instant.fromEpochMilliseconds(row.started_at),
                        endedAt = row.ended_at?.let(Instant::fromEpochMilliseconds),
                    )
                } catch (_: Exception) {
                    null
                }
            if (record == null) {
                // Retire the unreadable row; its decisions (if any) stay in the
                // decision table, only this resume pointer is dropped.
                sessions.endSession(clock.now().toEpochMilliseconds(), row.id)
            }
            record
        }

    override suspend fun skippedIds(sessionId: String): Set<String> =
        withContext(dispatcher) {
            database
                .await()
                .sessionQueries
                .skippedIds(sessionId)
                .executeAsList()
                .toSet()
        }
}
