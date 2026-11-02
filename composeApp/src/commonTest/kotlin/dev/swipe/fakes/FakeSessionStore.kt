package dev.swipe.fakes

import dev.swipe.data.SessionStore
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SessionRecord

/**
 * In-memory [SessionStore] for tests (§8.2). [create] hands out `session-1`, `session-2`, …
 * and stamps `startedAt`/`endedAt` from [clock]. Records every call; each `failNext…`
 * switch makes the next matching call throw once.
 *
 * [skippedIds] reads the shared [FakeSessionSkips] table, so skips recorded through
 * [FakeDecisionStore.record] are visible when both fakes share one instance.
 */
class FakeSessionStore(
    private val clock: FakeClock = FakeClock(),
    val skips: FakeSessionSkips = FakeSessionSkips(),
) : SessionStore {
    /** Every [create] call, oldest first. */
    val createCalls = mutableListOf<Triple<DeckSource, MediaTypeFilter, Long>>()

    /** Every [updatePosition] call, oldest first. */
    val updatePositionCalls = mutableListOf<Pair<String, Int>>()

    /** Every [end] call, oldest first. */
    val endCalls = mutableListOf<String>()

    var failNextCreate: Throwable? = null
    var failNextUpdatePosition: Throwable? = null
    var failNextEnd: Throwable? = null

    /** All stored sessions, for assertions. */
    val stored: List<SessionRecord> get() = sessions.values.toList()

    private var nextId = 1
    private val sessions = mutableMapOf<String, SessionRecord>()

    override suspend fun create(
        source: DeckSource,
        typeFilter: MediaTypeFilter,
        seed: Long,
    ): String {
        createCalls += Triple(source, typeFilter, seed)
        failNextCreate?.let {
            failNextCreate = null
            throw it
        }
        val id = "session-$nextId"
        nextId++
        sessions[id] =
            SessionRecord(
                id = id,
                source = source,
                typeFilter = typeFilter,
                seed = seed,
                position = 0,
                startedAt = clock.now(),
                endedAt = null,
            )
        return id
    }

    override suspend fun updatePosition(
        id: String,
        position: Int,
    ) {
        updatePositionCalls += id to position
        failNextUpdatePosition?.let {
            failNextUpdatePosition = null
            throw it
        }
        sessions[id]?.let { sessions[id] = it.copy(position = position) }
    }

    override suspend fun end(id: String) {
        endCalls += id
        failNextEnd?.let {
            failNextEnd = null
            throw it
        }
        sessions[id]?.let { sessions[id] = it.copy(endedAt = clock.now()) }
    }

    // Stable ascending sort: ties on a fixed clock resolve to the most recently
    // created session (map iteration is insertion order), matching FR-10 intent.
    override suspend fun openSession(): SessionRecord? =
        sessions.values
            .filter { it.endedAt == null }
            .sortedBy { it.startedAt }
            .lastOrNull()

    override suspend fun skippedIds(sessionId: String): Set<String> = skips.idsOf(sessionId)

    /** Seed a skip directly, without going through [FakeDecisionStore.record] (FR-14). */
    fun addSkip(
        sessionId: String,
        assetId: String,
    ) {
        skips.add(sessionId, assetId)
    }
}
