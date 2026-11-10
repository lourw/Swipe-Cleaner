package dev.swipe.data.image

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The LRU behind the image pipeline (WP-1.5). Plain `String` values stand in for decoded
 * bitmaps — the cache is generic, so the eviction contract is what gets tested (NFR-3).
 */
class BitmapLruCacheTest {
    private val size = IntSize(1024, 1024)

    private val evicted = mutableListOf<Pair<BitmapCacheKey, String>>()

    private fun cache(capacity: Int = BitmapLruCache.DEFAULT_CAPACITY) =
        BitmapLruCache<String>(capacity = capacity, onEvict = { key, value -> evicted += key to value })

    private fun key(id: String) = BitmapCacheKey(id, size)

    @Test
    fun `NFR-3 cache never holds more than 5 bitmaps`() =
        runTest {
            val cache = cache()
            repeat(8) { i -> cache.put(key("id-$i"), "v$i") }

            assertEquals(5, cache.size())
            // The three that fell out, oldest first; their onEvict fired with the right key.
            assertEquals(listOf("id-0", "id-1", "id-2"), evicted.map { it.first.id })
            assertEquals(listOf("v0", "v1", "v2"), evicted.map { it.second })
            // The five newest survive; the evicted ones are gone.
            for (i in 0 until 3) assertNull(cache.get(key("id-$i")))
            for (i in 3 until 8) assertEquals("v$i", cache.get(key("id-$i")))
        }

    @Test
    fun `NFR-3 least recently used bitmap is evicted first`() =
        runTest {
            val cache = cache()
            for (i in 0 until 5) cache.put(key("id-$i"), "v$i")
            // Touch id-0 so id-1 becomes the least recently used.
            assertEquals("v0", cache.get(key("id-0")))

            cache.put(key("id-5"), "v5")

            // id-1 goes, not id-0. Order after: get(id-0) moved it to most-recently-used,
            // so the remaining keys oldest-first are id-2, id-3, id-4, id-0, then id-5.
            assertEquals(listOf(key("id-1") to "v1"), evicted)
            assertEquals(listOf("id-2", "id-3", "id-4", "id-0", "id-5"), cache.keysOldestFirst().map { it.id })
        }

    @Test
    fun `NFR-3 re-putting a key keeps the cached value and releases the new one`() =
        runTest {
            val cache = cache()
            cache.put(key("a"), "old")

            val held = cache.put(key("a"), "new")

            // The cached value survives the replace — callers emit what put returns.
            // The redundant decode is the value that gets released.
            assertEquals("old", held)
            assertEquals(listOf(key("a") to "new"), evicted)
            assertEquals(1, cache.size())
            assertEquals("old", cache.get(key("a")))
        }

    @Test
    fun `NFR-3 same-key puts never close a bitmap already handed out`() =
        runTest {
            val cache = cache()
            cache.put(key("a"), "first")
            val handedOut = cache.get(key("a"))
            checkNotNull(handedOut)

            repeat(3) { i -> cache.put(key("a"), "redundant-$i") }

            // onEvict only ever saw the redundant decodes, never the handed-out bitmap,
            // and the cache still holds it (WP-7.1).
            assertEquals(listOf("redundant-0", "redundant-1", "redundant-2"), evicted.map { it.second })
            assertEquals("first", handedOut)
            assertEquals("first", cache.get(key("a")))
        }

    @Test
    fun `NFR-3 two collectors on one key never get a closed bitmap`() =
        runTest {
            val cache = cache()
            // The overlap a card promoted from "next" to "current" creates: two collectors
            // for one key both miss the cache, decode concurrently, then both put. Each
            // emits what its put returned; neither may emit a released value.
            val bothMissed = CompletableDeferred<Unit>()
            var misses = 0
            val emitted = mutableListOf<String>()

            val collectors =
                (1..2).map { i ->
                    launch {
                        val cached = cache.get(key("a"))
                        if (cached == null) {
                            misses++
                            if (misses == 2) bothMissed.complete(Unit) else bothMissed.await()
                        }
                        emitted += cached ?: cache.put(key("a"), "decode-$i")
                    }
                }
            collectors.joinAll()

            val winner = cache.get(key("a"))
            // Both collectors draw the bitmap the cache holds; the loser's redundant
            // decode is the only value ever released.
            assertEquals(listOf(winner, winner), emitted)
            val loser = if (winner == "decode-1") "decode-2" else "decode-1"
            assertEquals(listOf(key("a") to loser), evicted)
        }

    @Test
    fun `NFR-3 same-key put does not spend the budget and eviction still closes`() =
        runTest {
            val cache = cache(capacity = 2)
            cache.put(key("a"), "a1")
            val kept = cache.put(key("a"), "a2")

            // The redundant decode is released and the key still counts once.
            assertEquals("a1", kept)
            assertEquals(1, cache.size())

            cache.put(key("b"), "b1")
            cache.put(key("c"), "c1")

            // The budget still binds: a is the least-recently-used victim and closes,
            // after its earlier redundant twin.
            assertEquals(2, cache.size())
            assertEquals(listOf(key("a") to "a2", key("a") to "a1"), evicted)
            assertEquals(listOf("b1", "c1"), listOf(cache.get(key("b")), cache.get(key("c"))))
        }
}
