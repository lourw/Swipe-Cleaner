package dev.swipe.data.image

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Identity of a [BitmapLruCache] entry: one asset loaded at one requested pixel budget
 * (§6.5 step 3). Two requests for the same asset at different sizes are different
 * bitmaps, so the size is part of the key (NFR-3).
 */
data class BitmapCacheKey(
    val id: String,
    val targetPx: IntSize,
)

/**
 * Least-recently-used cache of decoded bitmaps (WP-1.5; NFR-3). It holds at most
 * [capacity] entries (5 by default, the NFR-3 budget for full-screen bitmaps); inserting
 * past capacity evicts the entry that went longest without a get-or-put, and each evicted
 * value is handed to [onEvict] so the platform layer can release it (an
 * `ImageBitmap.close()` on iOS).
 *
 * Generic over the value type [V] so `commonMain` stays free of bitmap / platform types —
 * `iosMain` instantiates it over `ImageBitmap`, tests over plain values.
 *
 * **Concurrency.** [get] and [put] are `suspend` and serialised through a single [Mutex],
 * which makes the cache safe for the concurrent access it sees: PhotoKit delivers image
 * results on [kotlinx.coroutines.Dispatchers.Default], so several coroutines get and put
 * at once. [Mutex] is chosen over `expect/actual` platform locks (hard rule 5, and it adds
 * no library) and over confinement because the access pattern is genuinely multi-threaded.
 * [onEvict] runs *after* the lock is released, so a slow release never blocks other
 * callers.
 */
class BitmapLruCache<V>(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val onEvict: (key: BitmapCacheKey, value: V) -> Unit = { _, _ -> },
) {
    init {
        require(capacity > 0) { "BitmapLruCache capacity must be positive, was $capacity" }
    }

    private val mutex = Mutex()

    /** Insertion-ordered map used as an LRU list: the first entry is the least recently used. */
    private val entries = LinkedHashMap<BitmapCacheKey, V>()

    /** Returns the cached value for [key] and marks it most recently used, or null if absent. */
    suspend fun get(key: BitmapCacheKey): V? =
        mutex.withLock {
            // remove + re-insert moves the entry to the tail (most recently used).
            val value = entries.remove(key) ?: return@withLock null
            entries[key] = value
            value
        }

    /**
     * Stores [value] under [key] as most recently used. If [key] already held a different
     * value, that value is evicted. Any entries over [capacity] after the insert are evicted,
     * least recently used first; every evicted value is passed to [onEvict] off the lock.
     */
    suspend fun put(
        key: BitmapCacheKey,
        value: V,
    ) {
        val evicted = mutableListOf<Pair<BitmapCacheKey, V>>()
        mutex.withLock {
            entries.remove(key)?.let { evicted += key to it }
            entries[key] = value
            while (entries.size > capacity) {
                val lru = entries.entries.first()
                entries.remove(lru.key)
                evicted += lru.key to lru.value
            }
        }
        evicted.forEach { (k, v) -> onEvict(k, v) }
    }

    /** Number of entries currently held. */
    suspend fun size(): Int = mutex.withLock { entries.size }

    /** Snapshot of held keys, least recently used first. Useful for assertions and diagnostics. */
    suspend fun keysOldestFirst(): List<BitmapCacheKey> = mutex.withLock { entries.keys.toList() }

    companion object {
        /** NFR-3: at most 5 decoded full-screen bitmaps in memory at once. */
        const val DEFAULT_CAPACITY = 5
    }
}
