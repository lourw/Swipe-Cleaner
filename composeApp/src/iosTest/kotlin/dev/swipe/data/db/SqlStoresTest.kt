@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.swipe.data.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import app.cash.turbine.test
import dev.swipe.db.SwipeDatabase
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SessionCounts
import dev.swipe.domain.model.Stats
import dev.swipe.fakes.FakeClock
import dev.swipe.platform.DatabaseDriverFactory
import kotlinx.cinterop.refTo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * SQLDelight store tests on the iOS simulator with a native driver (REQUIREMENTS §8.2
 * "DB" level). Most tests use a private in-memory database; the FR-20 durability tests
 * use real files in `NSTemporaryDirectory` through [DatabaseDriverFactory], reopened
 * with a fresh driver and store the way a relaunch would (FR-20).
 */
class SqlStoresTest {
    private val driver: NativeSqliteDriver = inMemoryDriver(SwipeDatabase.Schema)
    private val database = SwipeDatabase(driver)
    private val clock = FakeClock()

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ——— FR-20: durability across store instances (real file, fresh driver) ———

    @Test
    fun `FR-20 recorded decision survives a new store instance`() =
        runTest {
            val name = "fr20-${Uuid.random()}.db"
            val factory = DatabaseDriverFactory()
            val first = factory.createIn(NSTemporaryDirectory(), name)
            SqlDecisionStore(SwipeDatabase(first), clock).record("kept-1", Decision.KEEP, "session-1")
            first.close()

            val reopened = SqlDecisionStore(SwipeDatabase(factory.createIn(NSTemporaryDirectory(), name)), clock)
            assertEquals(setOf("kept-1"), reopened.decidedIds())
            // KEEP is queued nowhere: the delete queue stays empty.
            assertEquals(emptyList(), reopened.deleteQueue().first())
            deleteTempDatabase(name)
        }

    @Test
    fun `FR-20 session keeps seed and position across a new store instance`() =
        runTest {
            val name = "fr20-session-${Uuid.random()}.db"
            val factory = DatabaseDriverFactory()
            val first = factory.createIn(NSTemporaryDirectory(), name)
            val sessions = SqlSessionStore(SwipeDatabase(first), clock)
            val id = sessions.create(DeckSource.All, MediaTypeFilter.PHOTOS, seed = 12_345L)
            sessions.updatePosition(id, 7)
            first.close()

            val reopened = SqlSessionStore(SwipeDatabase(factory.createIn(NSTemporaryDirectory(), name)), clock)
            val open = reopened.openSession()
            assertEquals(id, open?.id)
            assertEquals(DeckSource.All, open?.source)
            assertEquals(MediaTypeFilter.PHOTOS, open?.typeFilter)
            assertEquals(12_345L, open?.seed)
            assertEquals(7, open?.position)
            deleteTempDatabase(name)
        }

    @Test
    fun `FR-20 a corrupt database is moved aside and a fresh one opens`() =
        runTest {
            val name = "fr20-corrupt-${Uuid.random()}.db"
            val path = NSTemporaryDirectory() + name
            val manager = NSFileManager.defaultManager
            val garbage = "not a sqlite database, just noise for the corrupt-file test".repeat(8).encodeToByteArray()
            val handle = fopen(path, "wb")
            assertTrue(handle != null)
            assertTrue(fwrite(garbage.refTo(0), 1UL, garbage.size.toULong(), handle) == garbage.size.toULong())
            fclose(handle)

            val driver = DatabaseDriverFactory().createIn(NSTemporaryDirectory(), name)
            // The fresh database opens with the current schema and no decisions...
            assertEquals(emptyList(), SwipeDatabase(driver).decisionQueries.decidedIds().executeAsList())
            // ...and the corrupt file was moved aside instead of crash-looping.
            assertTrue(manager.fileExistsAtPath("$path$CORRUPT_SUFFIX"))
            driver.close()
            deleteTempDatabase(name)
        }

    // ——— FR-9: decidedIds covers KEEP + DELETE only ———

    @Test
    fun `FR-9 decidedIds contains KEEP and DELETE but not SKIP or DELETED`() =
        runTest {
            val store = SqlDecisionStore(database, clock)
            store.record("keep-1", Decision.KEEP, "session-1")
            store.record("queued-1", Decision.DELETE, "session-1")
            store.record("skipped-1", Decision.SKIP, "session-1")
            store.record("gone-1", Decision.DELETE, "session-1")
            store.markDeleted(listOf("gone-1"))

            assertEquals(setOf("keep-1", "queued-1"), store.decidedIds())
        }

    // ——— FR-23: queue order ———

    @Test
    fun `FR-23 deleteQueue is newest decision first`() =
        runTest {
            val store = SqlDecisionStore(database, clock)
            store.record("older", Decision.DELETE, "session-1")
            clock.advanceBy(1.seconds)
            store.record("newer", Decision.DELETE, "session-1")
            clock.advanceBy(1.seconds)
            store.record("kept", Decision.KEEP, "session-1")

            assertEquals(listOf("newer", "older"), store.deleteQueue().first())
        }

    @Test
    fun `FR-23 deleteQueue ties fall back to newest recorded first`() =
        runTest {
            // Fixed clock: equal decided_at values resolve by insertion order (rowid DESC),
            // mirroring FakeDecisionStore's tie-break.
            val store = SqlDecisionStore(database, clock)
            store.record("first", Decision.DELETE, "session-1")
            store.record("second", Decision.DELETE, "session-1")

            assertEquals(listOf("second", "first"), store.deleteQueue().first())
        }

    @Test
    fun `FR-23 deleteQueue re-emits when the queue changes`() =
        runTest {
            val store = SqlDecisionStore(database, clock)
            store.deleteQueue().test {
                assertEquals(emptyList(), awaitItem())
                store.record("a", Decision.DELETE, "session-1")
                assertEquals(listOf("a"), awaitItem())
                store.remove("a", "session-1")
                assertEquals(emptyList(), awaitItem())
            }
        }

    // ——— FR-30: clearKeeps spares the queue ———

    @Test
    fun `FR-30 clearKeeps leaves the delete queue untouched`() =
        runTest {
            val store = SqlDecisionStore(database, clock)
            store.record("kept-1", Decision.KEEP, "session-1")
            store.record("queued-1", Decision.DELETE, "session-1")

            store.clearKeeps()

            assertEquals(setOf("queued-1"), store.decidedIds())
            assertEquals(listOf("queued-1"), store.deleteQueue().first())
        }

    // ——— FR-15: undo clears whichever table holds the row ———

    @Test
    fun `FR-15 remove deletes a decision and a session skip`() =
        runTest {
            val decisions = SqlDecisionStore(database, clock)
            val sessions = SqlSessionStore(database, clock)
            decisions.record("queued-1", Decision.DELETE, "session-1")
            decisions.record("skipped-1", Decision.SKIP, "session-1")

            decisions.remove("queued-1", "session-1")
            decisions.remove("skipped-1", "session-1")

            assertEquals(emptySet(), decisions.decidedIds())
            assertEquals(emptySet(), sessions.skippedIds("session-1"))
        }

    @Test
    fun `FR-15 remove clears the skip of its session only`() =
        runTest {
            // The resume flow (§6.5 step 1) keeps several sessions alive; undoing a
            // skip in one must leave the same asset's skip row in another (FR-14).
            val decisions = SqlDecisionStore(database, clock)
            val sessions = SqlSessionStore(database, clock)
            decisions.record("skipped-1", Decision.SKIP, "session-1")
            decisions.record("skipped-1", Decision.SKIP, "session-2")

            decisions.remove("skipped-1", "session-1")

            assertEquals(emptySet(), sessions.skippedIds("session-1"))
            assertEquals(setOf("skipped-1"), sessions.skippedIds("session-2"))
        }

    // ——— FR-21: per-session counts ———

    @Test
    fun `FR-21 sessionCounts counts this session keeps and queue only`() =
        runTest {
            val store = SqlDecisionStore(database, clock)
            store.record("a", Decision.KEEP, "session-1")
            store.record("b", Decision.DELETE, "session-1")
            store.record("c", Decision.SKIP, "session-1") // never in decision (§7)
            store.record("d", Decision.KEEP, "session-2")
            store.record("e", Decision.DELETE, "session-1")
            store.markDeleted(listOf("e")) // DELETED is neither kept nor queued

            assertEquals(SessionCounts(kept = 1, queued = 1), store.sessionCounts("session-1"))
            assertEquals(SessionCounts(kept = 1, queued = 0), store.sessionCounts("session-2"))
            assertEquals(SessionCounts(kept = 0, queued = 0), store.sessionCounts("session-3"))
        }

    // ——— FR-10: stats from COUNT queries ———

    @Test
    fun `FR-10 stats counts reviewed kept deleted`() =
        runTest {
            val store = SqlDecisionStore(database, clock)
            store.record("a", Decision.KEEP, "session-1")
            store.record("b", Decision.DELETE, "session-1")
            store.record("c", Decision.SKIP, "session-1") // never in decision (§7), never counted
            store.record("d", Decision.DELETE, "session-1")
            store.markDeleted(listOf("d"))

            // reviewed = KEEP + pending DELETE + DELETED = 3; SKIP excluded (FR-10, §7).
            assertEquals(Stats(reviewed = 3, kept = 1, deleted = 1, queued = 1), store.stats().first())
        }

    // ——— FR-14 / FR-20: session skip list and resume bookkeeping ———

    @Test
    fun `FR-14 skippedIds returns only the skips of that session`() =
        runTest {
            val decisions = SqlDecisionStore(database, clock)
            val sessions = SqlSessionStore(database, clock)
            decisions.record("a", Decision.SKIP, "session-1")
            decisions.record("b", Decision.SKIP, "session-2")

            assertEquals(setOf("a"), sessions.skippedIds("session-1"))
            assertEquals(setOf("b"), sessions.skippedIds("session-2"))
        }

    @Test
    fun `FR-20 an ended session is no longer resumed`() =
        runTest {
            val sessions = SqlSessionStore(database, clock)
            val id = sessions.create(DeckSource.All, MediaTypeFilter.ALL, seed = 1L)
            assertEquals(id, sessions.openSession()?.id)

            sessions.end(id)

            assertEquals(null, sessions.openSession())
        }
}

/** Removes the temp database and its sidecars written by [DatabaseDriverFactory]. */
private fun deleteTempDatabase(name: String) {
    val manager = NSFileManager.defaultManager
    for (suffix in listOf("", "-wal", "-shm")) {
        for (corrupt in listOf("", CORRUPT_SUFFIX)) {
            manager.removeItemAtPath(NSTemporaryDirectory() + name + suffix + corrupt, error = null)
        }
    }
}

private const val CORRUPT_SUFFIX = ".corrupt"
