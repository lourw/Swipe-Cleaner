@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-9.4 (NFR-2, NFR-3, §6.5 step 4): [WarmItemCache] — the core of the iOS
 * `VideoItemCache` — must apply `preload`/`cancel` in call order (a cancel can
 * no longer run before its preload and strand an entry forever), must never
 * tear down a load a mounted player is awaiting, and must never hold more ids
 * than the prefetch window.
 *
 * Every test drives a [RecordingLoader] on a shared `StandardTestDispatcher`,
 * so task interleaving is fully deterministic: `runCurrent` runs exactly the
 * queued transitions, and each load completes only when the test says so.
 */
class WarmItemCacheTest {
    /**
     * A loader that hands every call a [CompletableDeferred] the test resolves,
     * recording cancellations the way PhotoKit's request would die: the awaiting
     * loader coroutine gets a [CancellationException].
     */
    private class RecordingLoader {
        class Call(
            val id: String,
            val result: CompletableDeferred<String?>,
        )

        val calls = mutableListOf<Call>()
        val cancelledIds = mutableListOf<String>()

        val load: suspend (String) -> String? = { id ->
            val call = Call(id, CompletableDeferred())
            calls += call
            try {
                call.result.await()
            } catch (cancellation: CancellationException) {
                cancelledIds += id
                throw cancellation
            }
        }
    }

    private fun newCache(
        loader: RecordingLoader,
        scheduler: TestCoroutineScheduler,
        maxEntries: Int = DEFAULT_WINDOW,
    ): WarmItemCache<String> =
        WarmItemCache(
            maxEntries = maxEntries,
            ioDispatcher = StandardTestDispatcher(scheduler),
            load = loader.load,
        )

    @Test
    fun `NFR-3 preload then cancel leaves nothing cached even when both are queued`() =
        runTest {
            // The WP-9.4 race: preload and cancel were both fire-and-forget on a
            // pool, so cancel's body could run first and the preload's entry then
            // survived the eviction that was meant to remove it — forever.
            val loader = RecordingLoader()
            val cache = newCache(loader, testScheduler)
            cache.preload("a")
            cache.cancel("a")
            advanceUntilIdle()
            assertEquals(emptySet(), cache.cachedIds())
            assertEquals(listOf("a"), loader.cancelledIds)
            cache.close()
        }

    @Test
    fun `NFR-3 cancel before its preload runs is safe and the preload still warms the id`() =
        runTest {
            // The stop-before-start analog: a cancel for an id the cache never saw
            // is a no-op, and the later preload (the id re-entered the window —
            // e.g. after an undo) still warms it.
            val loader = RecordingLoader()
            val cache = newCache(loader, testScheduler)
            cache.cancel("a")
            cache.preload("a")
            advanceUntilIdle()
            assertEquals(setOf("a"), cache.cachedIds())
            assertEquals(listOf("a"), loader.calls.map { it.id })
            assertTrue(loader.cancelledIds.isEmpty())
            cache.close()
        }

    @Test
    fun `NFR-3 window churn keeps exactly the ids still in the window`() =
        runTest {
            val loader = RecordingLoader()
            val cache = newCache(loader, testScheduler, maxEntries = 4)
            cache.preload("a")
            cache.preload("b")
            cache.preload("c")
            cache.cancel("a")
            cache.preload("d")
            advanceUntilIdle()
            assertEquals(setOf("b", "c", "d"), cache.cachedIds())
            assertEquals(listOf("a"), loader.cancelledIds)
            cache.close()
        }

    @Test
    fun `NFR-2 cancel while a mounted player awaits still delivers the item`() =
        runTest {
            // The suspected defect, now pinned: the id left the window while the
            // mounted player was still awaiting its item. Cancelling the shared
            // Deferred used to deliver a CancellationException to the player,
            // stranding the card on its poster. Release-then-evict instead.
            val loader = RecordingLoader()
            val cache = newCache(loader, testScheduler)
            val player = async { cache.item("a") }
            runCurrent()
            assertEquals(1, loader.calls.size)
            cache.cancel("a")
            runCurrent()
            assertTrue(loader.cancelledIds.isEmpty(), "the awaited load must not be torn down")
            loader.calls[0].result.complete("item-a")
            advanceUntilIdle()
            assertEquals("item-a", player.await())
            // The eviction still took effect: the completed entry is not (re-)cached.
            assertEquals(emptySet(), cache.cachedIds())
            cache.close()
        }

    @Test
    fun `NFR-2 every awaiter gets the item across a racing cancel`() =
        runTest {
            val loader = RecordingLoader()
            val cache = newCache(loader, testScheduler)
            val first = async { cache.item("a") }
            val second = async { cache.item("a") }
            runCurrent()
            assertEquals(1, loader.calls.size, "one shared load per id")
            cache.cancel("a")
            runCurrent()
            loader.calls[0].result.complete("item-a")
            advanceUntilIdle()
            assertEquals("item-a", first.await())
            assertEquals("item-a", second.await())
            assertTrue(loader.cancelledIds.isEmpty())
            cache.close()
        }

    @Test
    fun `NFR-3 releasing the last lease of a cancelled id stops its load`() =
        runTest {
            // §6.8: the fetch of a departed id must not run on once nothing wants
            // it — when the player itself goes away, the last lease releases and
            // the (retired) load is cancelled there.
            val loader = RecordingLoader()
            val cache = newCache(loader, testScheduler)
            val player = async { cache.item("a") }
            runCurrent()
            cache.cancel("a")
            runCurrent()
            assertTrue(loader.cancelledIds.isEmpty())
            player.cancel()
            advanceUntilIdle()
            assertEquals(listOf("a"), loader.cancelledIds)
            cache.close()
        }

    @Test
    fun `NFR-3 the cache never holds more ids than the window`() =
        runTest {
            val loader = RecordingLoader()
            val cache = newCache(loader, testScheduler, maxEntries = 4)
            (1..10).forEach { cache.preload("id-$it") }
            advanceUntilIdle()
            assertEquals(setOf("id-6", "id-7", "id-8", "id-9"), cache.cachedIds())
            // The six that fell out of the bound had their loads cancelled, oldest first.
            assertEquals((1..6).map { "id-$it" }, loader.cancelledIds)
            cache.close()
        }

    @Test
    fun `NFR-3 a warm id is never loaded twice`() =
        runTest {
            val loader = RecordingLoader()
            val cache = newCache(loader, testScheduler)
            cache.preload("a")
            advanceUntilIdle()
            cache.preload("a")
            advanceUntilIdle()
            assertEquals(1, loader.calls.size)
            loader.calls[0].result.complete("item-a")
            advanceUntilIdle()
            assertEquals("item-a", cache.item("a"))
            assertEquals(1, loader.calls.size)
            cache.close()
        }

    @Test
    fun `NFR-3 a failed load is not cached and the next read retries`() =
        runTest {
            // R6: nil results stay uncached so a later read can succeed.
            val loader = RecordingLoader()
            val cache = newCache(loader, testScheduler)
            val first = async { cache.item("a") }
            runCurrent()
            loader.calls[0].result.complete(null)
            assertNull(first.await())
            advanceUntilIdle()
            assertEquals(emptySet(), cache.cachedIds())
            val retry = async { cache.item("a") }
            runCurrent()
            assertEquals(2, loader.calls.size)
            loader.calls[1].result.complete("item-a")
            assertEquals("item-a", retry.await())
            cache.close()
        }

    private companion object {
        /** §6.5 step 3's window (current + next 3), the production default. */
        const val DEFAULT_WINDOW = 4
    }
}
