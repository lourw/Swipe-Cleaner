package dev.swipe.domain.usecase

import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.fakes.FakeClock
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeMediaLibrary
import dev.swipe.fakes.FakeSessionSkips
import dev.swipe.fakes.FakeSessionStore
import dev.swipe.fakes.buildDeckUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** A deterministic non-shuffled source, so the deck order is asset-1, asset-2, … */
private val source: DeckSource = DeckSource.Smart(SmartFilter.SCREENSHOTS)

/**
 * WP-9.2: [StartSessionUseCase] owns §6.5 step 1 — resume matching (FR-20), the
 * skip subtraction and reviewed-base maths (FR-19, WP-6.4), and closing the rows
 * a fresh start leaves behind. The deck *ordering* itself is
 * [BuildDeckUseCaseTest]'s job; here the deck is only passed through.
 */
class StartSessionUseCaseTest {
    private class Env(
        itemCount: Int = 5,
    ) {
        val clock = FakeClock()
        val skips = FakeSessionSkips()
        val store = FakeDecisionStore(clock, skips)
        val sessionStore = FakeSessionStore(clock, skips)
        val library = FakeMediaLibrary((1..itemCount).map { makePhoto("asset-$it", (itemCount - it + 1).toLong()) })
        val useCase = StartSessionUseCase(buildDeckUseCase(library, store), store, sessionStore)
    }

    @Test
    fun `FR-20 a matching open session resumes on its seed without creating a row`() =
        runTest {
            val env = Env()
            env.sessionStore.create(source, MediaTypeFilter.ALL, seed = 7L)

            val start = env.useCase(source, MediaTypeFilter.ALL) { error("a resume must not seed") }

            assertEquals("session-1", start.sessionId)
            // Only the seeded create — resume never creates a row.
            assertEquals(1, env.sessionStore.createCalls.size)
            assertEquals(listOf("asset-1", "asset-2", "asset-3", "asset-4", "asset-5"), start.deck)
            assertEquals(0, start.reviewedBase)
        }

    @Test
    fun `FR-30 a resumed Shuffle session's rebuilt deck includes the ids a reset cleared`() =
        runTest {
            // FR-30: Reset all decisions clears KEEP rows only, so the next rebuild
            // (FR-20) hands the cleared ids back to the deck while a queued DELETE
            // stays decided away.
            val env = Env(itemCount = 5)
            env.sessionStore.create(DeckSource.All, MediaTypeFilter.ALL, seed = 7L)
            env.store.record("asset-1", Decision.KEEP, "session-1")
            env.store.record("asset-2", Decision.KEEP, "session-1")
            env.store.record("asset-3", Decision.DELETE, "session-1")

            val before = env.useCase(DeckSource.All, MediaTypeFilter.ALL) { error("a resume must not seed") }
            // The keeps and the queued delete are all out of the deck.
            assertEquals(setOf("asset-4", "asset-5"), before.deck.toSet())

            env.store.clearKeeps()
            val after = env.useCase(DeckSource.All, MediaTypeFilter.ALL) { error("a resume must not seed") }

            assertEquals("session-1", after.sessionId)
            assertEquals(4, after.deck.size)
            assertTrue("asset-1" in after.deck)
            assertTrue("asset-2" in after.deck)
            assertTrue("asset-3" !in after.deck)
        }

    @Test
    fun `FR-19 the reviewed base adds the stored counts and this session's skips`() =
        runTest {
            val env = Env()
            env.sessionStore.create(source, MediaTypeFilter.ALL, seed = 7L)
            // KEEP is decided (out of the rebuilt deck via BuildDeckUseCase, FR-9);
            // the skip is subtracted here; the DELETE counts toward reviewed even
            // though its row may commit mid-session (WP-6.4, FR-26).
            env.store.record("asset-1", Decision.KEEP, "session-1")
            env.store.record("asset-2", Decision.DELETE, "session-1")
            env.sessionStore.addSkip("session-1", "asset-3")

            val start = env.useCase(source, MediaTypeFilter.ALL) { error("a resume must not seed") }

            assertEquals("session-1", start.sessionId)
            assertEquals(listOf("asset-4", "asset-5"), start.deck)
            // reviewed rows (2) + skipped (1).
            assertEquals(3, start.reviewedBase)
        }

    @Test
    fun `WP-9-2 a renamed album still resumes on its id`() =
        runTest {
            val env = Env()
            env.sessionStore.create(DeckSource.Album("album-1", "Trip"), MediaTypeFilter.ALL, seed = 7L)

            val start =
                env.useCase(DeckSource.Album("album-1", "Trip 2026"), MediaTypeFilter.ALL) {
                    error("a resume must not seed")
                }

            // Same album id, new title: same session, not a fresh row (FR-20).
            assertEquals("session-1", start.sessionId)
            assertEquals(1, env.sessionStore.createCalls.size)
        }

    @Test
    fun `FR-20 a source mismatch starts fresh and ends the row it leaves behind`() =
        runTest {
            val env = Env()
            env.sessionStore.create(source, MediaTypeFilter.ALL, seed = 7L)

            val start = env.useCase(DeckSource.All, MediaTypeFilter.ALL) { 11L }

            assertEquals("session-2", start.sessionId)
            // The seeded row plus the fresh one; the fresh one is `All`.
            assertEquals(2, env.sessionStore.createCalls.size)
            assertEquals(Triple(DeckSource.All, MediaTypeFilter.ALL, 11L), env.sessionStore.createCalls.last())
            assertEquals(listOf("session-2"), env.sessionStore.endOtherOpenSessionsCalls)
            assertNotNull(
                env.sessionStore.stored
                    .single { it.id == "session-1" }
                    .endedAt,
            )
            assertNull(
                env.sessionStore.stored
                    .single { it.id == "session-2" }
                    .endedAt,
            )
        }

    @Test
    fun `FR-20 a filter mismatch starts fresh too`() =
        runTest {
            val env = Env()
            env.sessionStore.create(source, MediaTypeFilter.ALL, seed = 7L)

            val start = env.useCase(source, MediaTypeFilter.PHOTOS) { 11L }

            assertEquals("session-2", start.sessionId)
        }

    @Test
    fun `WP-9-2 a fresh start ends other open rows and keeps its own row open`() =
        runTest {
            val env = Env()
            // Two stale rows from earlier source visits, plus the new one.
            env.sessionStore.create(DeckSource.All, MediaTypeFilter.ALL, seed = 1L)
            env.sessionStore.create(DeckSource.All, MediaTypeFilter.PHOTOS, seed = 2L)

            env.useCase(source, MediaTypeFilter.ALL) { 11L }

            assertEquals(listOf("session-3"), env.sessionStore.endOtherOpenSessionsCalls)
            assertEquals(
                setOf("session-1", "session-2"),
                env.sessionStore.stored
                    .filter { it.endedAt != null }
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun `WP-9-2 a failed create leaves the existing open row untouched`() =
        runTest {
            val env = Env()
            env.sessionStore.create(source, MediaTypeFilter.ALL, seed = 7L)
            env.sessionStore.failNextCreate = IllegalStateException("db down")

            assertFailsWith<IllegalStateException> { env.useCase(DeckSource.All, MediaTypeFilter.ALL) { 11L } }

            // Create-then-end: nothing was ended, the old row is still resumable.
            assertTrue(env.sessionStore.endOtherOpenSessionsCalls.isEmpty())
            assertNull(
                env.sessionStore.stored
                    .single()
                    .endedAt,
            )
        }
}

private fun makePhoto(
    id: String,
    index: Long,
): MediaItem =
    MediaItem(
        id = id,
        type = MediaType.PHOTO,
        createdAt = Instant.fromEpochMilliseconds(index * 1000L),
        durationMs = null,
        widthPx = 120,
        heightPx = 160,
        isFavorite = false,
        isScreenshot = true,
        burstId = null,
        isCloudOnly = null,
    )
