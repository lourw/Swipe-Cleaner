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
 *
 * **Ownership.** A value handed out by [get] or stored by [put] belongs to the cache: it
 * is closed only via [onEvict], and only once it has left the map. Callers must treat
 * emitted bitmaps as borrowed and must not close them. A [put] on a key the cache already
 * holds keeps the existing value — it may be on screen — and releases the redundant new
 * value through [onEvict] instead; callers emit the value [put] returns, never their own
 * decode. Overlapping collectors on one key are therefore safe: a bitmap the cache has
 * handed out is closed by eviction, never by a later put (WP-7.1; NFR-3).
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
     * Stores [value] under [key] as most recently used and returns the value the cache holds
     * for [key] afterwards — emit that, not [value]. If [key] already holds a value, the
     * existing one is kept (it may already be drawn by the UI) and [value] is released
     * through [onEvict] as redundant (WP-7.1; NFR-3). Any entries over [capacity] after an
     * insert are evicted, least recently used first; every released value — evicted and
     * redundant alike — is passed to [onEvict] off the lock.
     */
    suspend fun put(
        key: BitmapCacheKey,
        value: V,
    ): V {
        val evicted = mutableListOf<Pair<BitmapCacheKey, V>>()
        val held =
            mutex.withLock {
                val existing = entries.remove(key)
                if (existing != null) {
                    // Same-key put: re-insert the existing value at the MRU end and hand the
                    // redundant new one to onEvict. A value the cache has handed out is then
                    // closed only by eviction, never by a later put.
                    entries[key] = existing
                    evicted += key to value
                    existing
                } else {
                    entries[key] = value
                    while (entries.size > capacity) {
                        // Copy key/value out of the entry view *before* removing it: on
                        // Kotlin/Native an EntryRef reads through to the backing map and
                        // throws ConcurrentModificationException once the map is modified.
                        val lru = entries.entries.first()
                        val lruKey = lru.key
                        val lruValue = lru.value
                        entries.remove(lruKey)
                        evicted += lruKey to lruValue
                    }
                    value
                }
            }
        evicted.forEach { (k, v) -> onEvict(k, v) }
        return held
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
