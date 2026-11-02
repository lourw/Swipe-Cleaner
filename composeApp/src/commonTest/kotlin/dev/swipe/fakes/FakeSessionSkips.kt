package dev.swipe.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The `session_skip` table of REQUIREMENTS §7, shared between [FakeDecisionStore] (which
 * writes SKIP rows through `record`, §7 comment) and [FakeSessionStore.skippedIds] (which
 * reads them), so the two fakes stay consistent the way the real SQLDelight tables are.
 */
class FakeSessionSkips {
    private val state = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Live view of session id → skipped asset ids. */
    val bySession: StateFlow<Map<String, Set<String>>> = state

    /** Record that [assetId] was skipped in [sessionId] (FR-14). */
    fun add(
        sessionId: String,
        assetId: String,
    ) {
        state.update { current -> current + (sessionId to (current[sessionId].orEmpty() + assetId)) }
    }

    /** Drop [assetId] from every session, as undo does (FR-15). */
    fun removeEverywhere(assetId: String) {
        state.update { current -> current.mapValues { (_, ids) -> ids - assetId } }
    }

    /** Skipped ids of [sessionId] (FR-14). */
    fun idsOf(sessionId: String): Set<String> = state.value[sessionId].orEmpty()
}
