package dev.swipe.domain.usecase

import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SessionSummary
import dev.swipe.domain.model.SmartFilter
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeSessionSkips
import dev.swipe.fakes.FakeSessionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deterministic non-shuffled source (only its identity is stored on the row). */
private val sessionSource: DeckSource = DeckSource.Smart(SmartFilter.SCREENSHOTS)

/**
 * WP-9.2: [FinishSessionUseCase] owns the FR-21 summary maths over the stored
 * rows and the end stamping, including the two failure rules: a failed counts
 * query leaves the row open for a retry, and the ends themselves are best effort.
 */
class FinishSessionUseCaseTest {
    private class Env {
        val clock = FakeClock()
        val skips = FakeSessionSkips()
        val store = FakeDecisionStore(clock, skips)
        val sessionStore = FakeSessionStore(clock, skips)
        val useCase = FinishSessionUseCase(store, sessionStore)

        suspend fun openSession() = sessionStore.create(sessionSource, MediaTypeFilter.ALL, seed = 7L)
    }

    @Test
    fun `FR-21 the summary comes from the stored rows and the row ends`() =
        runTest {
            val env = Env()
            env.openSession()
            env.store.record("asset-1", Decision.KEEP, "session-1")
            env.store.record("asset-2", Decision.DELETE, "session-1")
            env.sessionStore.addSkip("session-1", "asset-3")

            val summary = env.useCase("session-1")

            assertEquals(SessionSummary(kept = 1, queued = 1, skipped = 1), summary)
            assertEquals(listOf("session-1"), env.sessionStore.endCalls)
        }

    @Test
    fun `FR-21 a failed counts query returns no summary and keeps the row open for a retry`() =
        runTest {
            val env = Env()
            env.openSession()
            env.store.failNextSessionCounts = IllegalStateException("db down")

            assertNull(env.useCase("session-1"))

            // No end stamp: resuming an exhausted session retries the summary.
            assertTrue(env.sessionStore.endCalls.isEmpty())
            assertNull(
                env.sessionStore.stored
                    .single()
                    .endedAt,
            )
        }

    @Test
    fun `FR-21 a failed end still returns the summary`() =
        runTest {
            val env = Env()
            env.openSession()
            env.store.record("asset-1", Decision.KEEP, "session-1")
            env.sessionStore.failNextEnd = IllegalStateException("db down")

            assertEquals(SessionSummary(kept = 1, queued = 0, skipped = 0), env.useCase("session-1"))
        }

    @Test
    fun `FR-21 endEmptyDeck stamps the row ended right away`() =
        runTest {
            val env = Env()
            env.openSession()

            env.useCase.endEmptyDeck("session-1")

            assertEquals(listOf("session-1"), env.sessionStore.endCalls)
        }

    @Test
    fun `FR-21 a failed empty-deck end is swallowed - the next open closes it again`() =
        runTest {
            val env = Env()
            env.openSession()
            env.sessionStore.failNextEnd = IllegalStateException("db down")

            // Must not throw: the deck still shows its empty state.
            env.useCase.endEmptyDeck("session-1")
        }
}
