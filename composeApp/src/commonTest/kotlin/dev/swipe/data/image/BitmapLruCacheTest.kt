package dev.swipe.data.image

import androidx.compose.ui.unit.IntSize
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
    fun `NFR-3 re-putting a key releases the replaced value only`() =
        runTest {
            val cache = cache()
            cache.put(key("a"), "old")
            cache.put(key("a"), "new")

            assertEquals(listOf(key("a") to "old"), evicted)
            assertEquals(1, cache.size())
            assertEquals("new", cache.get(key("a")))
        }
}
