package dev.swipe.fakes

import dev.swipe.data.DecisionStore
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.Stats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * In-memory [DecisionStore] for tests (§8.2). Records every call and every failure
 * switch (`failNext…`: when non-null the next matching call throws it and the switch resets).
 *
 * Mirrors §7: [record] with [Decision.SKIP] goes to [skips] (`session_skip`), never into
 * the decision rows; [remove] clears the id from both; [stats] counts over the rows.
 */
class FakeDecisionStore(
    private val clock: FakeClock = FakeClock(),
    /** The shared `session_skip` table; pass the same instance to [FakeSessionStore]. */
    val skips: FakeSessionSkips = FakeSessionSkips(),
) : DecisionStore {
    /** One stored row of the `decision` table (REQUIREMENTS §7). */
    data class Entry(
        val id: String,
        val decision: Decision,
        val decidedAt: Instant,
        val sessionId: String,
    )

    /** Every [record] call, oldest first. */
    val recordCalls = mutableListOf<Triple<String, Decision, String>>()

    /** Every [remove] call, oldest first. */
    val removeCalls = mutableListOf<String>()

    /** Every [markDeleted] call, oldest first. */
    val markDeletedCalls = mutableListOf<List<String>>()

    /** How many times [clearKeeps] was called. */
    var clearKeepsCalls = 0
        private set

    /** How many times [decidedIds] was called. */
    var decidedIdsCalls = 0
        private set

    var failNextRecord: Throwable? = null
    var failNextRemove: Throwable? = null
    var failNextDecidedIds: Throwable? = null
    var failNextMarkDeleted: Throwable? = null
    var failNextClearKeeps: Throwable? = null

    /** The current `decision` rows, in insertion order. */
    val entries: List<Entry> get() = rows.value

    private val rows = MutableStateFlow<List<Entry>>(emptyList())

    override suspend fun record(
        id: String,
        decision: Decision,
        sessionId: String,
    ) {
        recordCalls += Triple(id, decision, sessionId)
        failNextRecord?.let {
            failNextRecord = null
            throw it
        }
        if (decision == Decision.SKIP) {
            // SKIP never touches the decision table (§7 comment, FR-14).
            skips.add(sessionId, id)
            return
        }
        rows.update { current ->
            current.filterNot { it.id == id } + Entry(id, decision, clock.now(), sessionId)
        }
    }

    override suspend fun remove(id: String) {
        removeCalls += id
        failNextRemove?.let {
            failNextRemove = null
            throw it
        }
        rows.update { current -> current.filterNot { it.id == id } }
        skips.removeEverywhere(id)
    }

    override suspend fun decidedIds(): Set<String> {
        decidedIdsCalls++
        failNextDecidedIds?.let {
            failNextDecidedIds = null
            throw it
        }
        return rows.value
            .filter { it.decision == Decision.KEEP || it.decision == Decision.DELETE }
            .mapTo(mutableSetOf()) { it.id }
    }

    override fun deleteQueue(): Flow<List<String>> =
        rows.map { current ->
            current
                .filter { it.decision == Decision.DELETE }
                .sortedByDescending { it.decidedAt }
                .map { it.id }
        }

    override suspend fun markDeleted(ids: List<String>) {
        markDeletedCalls += ids
        failNextMarkDeleted?.let {
            failNextMarkDeleted = null
            throw it
        }
        rows.update { current ->
            current.map {
                if (it.id in ids && it.decision == Decision.DELETE) it.copy(decision = Decision.DELETED) else it
            }
        }
    }

    override suspend fun clearKeeps() {
        clearKeepsCalls++
        failNextClearKeeps?.let {
            failNextClearKeeps = null
            throw it
        }
        rows.update { current -> current.filterNot { it.decision == Decision.KEEP } }
    }

    override fun stats(): Flow<Stats> =
        rows.map { current ->
            Stats(
                reviewed = current.size,
                kept = current.count { it.decision == Decision.KEEP },
                deleted = current.count { it.decision == Decision.DELETED },
                queued = current.count { it.decision == Decision.DELETE },
            )
        }
}
