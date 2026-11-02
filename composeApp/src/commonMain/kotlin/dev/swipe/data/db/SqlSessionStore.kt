package dev.swipe.data.db

import dev.swipe.data.SessionStore
import dev.swipe.db.SessionQueries
import dev.swipe.db.SwipeDatabase
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SessionRecord
import kotlinx.coroutines.CoroutineDispatcher
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
 * writes rethrow driver failures (NFR-7).
 */
class SqlSessionStore(
    private val database: SwipeDatabase,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val json: Json = Json,
) : SessionStore {
    private val sessions: SessionQueries = database.sessionQueries

    override suspend fun create(
        source: DeckSource,
        typeFilter: MediaTypeFilter,
        seed: Long,
    ): String =
        withContext(dispatcher) {
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

    override suspend fun updatePosition(
        id: String,
        position: Int,
    ) {
        withContext(dispatcher) {
            sessions.updatePosition(position.toLong(), id)
        }
    }

    override suspend fun end(id: String) {
        withContext(dispatcher) {
            sessions.endSession(clock.now().toEpochMilliseconds(), id)
        }
    }

    override suspend fun openSession(): SessionRecord? =
        withContext(dispatcher) {
            sessions.selectOpen().executeAsOneOrNull()?.let { row ->
                SessionRecord(
                    id = row.id,
                    source = json.decodeFromString(DeckSource.serializer(), row.source),
                    typeFilter = MediaTypeFilter.valueOf(row.type_filter),
                    seed = row.seed,
                    position = row.position.toInt(),
                    startedAt = Instant.fromEpochMilliseconds(row.started_at),
                    endedAt = row.ended_at?.let(Instant::fromEpochMilliseconds),
                )
            }
        }

    override suspend fun skippedIds(sessionId: String): Set<String> =
        withContext(dispatcher) {
            sessions.skippedIds(sessionId).executeAsList().toSet()
        }
}
