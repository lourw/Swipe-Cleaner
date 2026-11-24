package dev.swipe.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A fire-and-forget warm-up cache whose state transitions run on a single-consumer
 * **FIFO command queue** — an actor (WP-9.4; NFR-2, NFR-3, §6.5 step 4). This is
 * the correctness core of the iOS `VideoItemCache`, extracted so the ordering is
 * testable against a fake loader.
 *
 * **Ordering (NFR-3).** Every [preload], [cancel], lease acquire, and release is a
 * closure handed to one consumer coroutine through an unlimited [Channel]; channel
 * delivery is FIFO by contract, so operations take effect in **call order** no
 * matter which thread each caller invokes them on. Before this, both fired onto a
 * pool and a `cancel(id)` could run before the `preload(id)` it followed, leaving
 * the warmed entry alive forever — nothing else ever names that id again.
 * `Dispatchers.Default.limitedParallelism(1)` was tried first and rejected: its
 * KDoc is explicit that it guarantees confinement but **not** execution order
 * between queued coroutines ("It is not a mutex!"), and interleaved loads exposed
 * exactly that in this WP's tests.
 *
 * **Leases (NFR-2).** [item] counts an *await lease* on the entry while a consumer
 * (the mounted player view) awaits its load. A [cancel] — or a window-eviction —
 * for a leased entry only *retires* it (out of the map, so nothing new attaches);
 * the shared `Deferred` is cancelled when the **last lease releases**, never from
 * under an awaiter. Cancelling an awaited `Deferred` used to deliver a
 * `CancellationException` to the mounted player instead of its item, stranding
 * the card on its poster frame.
 *
 * **Bound (NFR-3).** The cache holds at most [maxEntries] entries — the deck's
 * prefetch window (§6.5 step 3). Inserting beyond the bound retires the oldest
 * entry first (insertion order, so the id furthest behind the deck goes), which
 * makes the bound enforced rather than caller discipline: a lost or mismatched
 * [cancel] can no longer grow the map.
 *
 * **Failure.** A `null` load result is *not* cached: the next [item] call
 * retries, mirroring [MediaLoader]'s handling of a refused cloud download (R6).
 *
 * **Threading.** Loads run concurrently on [ioDispatcher] (the network wait must
 * not serialize, and PhotoKit stays off the main thread — AGENTS.md); the queue
 * consumer only ever runs non-suspending map transitions, so it can never stall
 * behind a slow fetch, and the entry map is touched by exactly one coroutine.
 */
class WarmItemCache<T>(
    /** Maximum entries held; inserting beyond it retires the oldest (NFR-3). */
    private val maxEntries: Int,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Produces the warm value for an id; `null` means not producible and is not cached. */
    private val load: suspend (id: String) -> T?,
) {
    init {
        require(maxEntries > 0) { "a warm cache must hold at least one entry" }
    }

    /** One in-flight-or-completed load per id, plus its lease/retirement state. */
    private class Entry<T>(
        val request: Deferred<T?>,
    ) {
        /** Awaiting consumers ([item] calls in flight); > 0 forbids cancelling [request]. */
        var leases = 0

        /** Removed from the map by a cancel/eviction while leased; its load dies with the last lease. */
        var retired = false
    }

    /** Loads run here, concurrently — the network/disk wait must not serialize (NFR-1). */
    private val loadScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * The actor: one consumer coroutine, one FIFO command queue. Commands are
     * non-suspending closures over the private state below, so the map is only
     * ever touched by this single consumer, in send order.
     */
    private val queueScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        queueScope.launch {
            // The closures below are pure map transitions (create/remove/retire,
            // Deferred.cancel) and cannot throw; a failed one would hang every
            // later item() await, so keep the loop alive if that ever changes.
            for (command in commands) {
                runCatching(command)
            }
        }
    }

    /** Insertion order doubles as eviction order (the oldest id leaves first). */
    private val items = mutableMapOf<String, Entry<T>>()

    /** Warm [id]; a no-op when an entry (warm, loading, or retired-pending) exists. */
    fun preload(id: String) {
        // trySend on an unlimited channel never suspends; it fails only after
        // close(), and the VideoPreloader contract is fire-and-forget (§6.4).
        commands.trySend { getOrCreate(id) }
    }

    /**
     * Drop [id]: cancels the in-flight load when nobody awaits it, otherwise
     * retires the entry so the load is cancelled the moment the last [item]
     * awaiter releases (NFR-2 — never tear a `Deferred` out from under a waiter).
     */
    fun cancel(id: String) {
        commands.trySend { retire(items.remove(id)) }
    }

    /**
     * The value for [id] — the warm entry when there is one, otherwise the load
     * starts here and is awaited. Null when the load failed or produced nothing
     * (the caller keeps showing its fallback); the failed entry is dropped so the
     * next call retries (R6).
     */
    suspend fun item(id: String): T? {
        val reply = CompletableDeferred<Entry<T>>()
        check(
            commands.trySend {
                reply.complete(getOrCreate(id).also { it.leases++ })
            }.isSuccess,
        ) { "the cache is closed" }
        // The acquire runs when the queue drains to it, and the drain never
        // awaits a load, so this is bounded; keep it NonCancellable so a caller
        // cancelled between "queued" and "acquired" cannot leak a half-taken
        // lease (which would pin the entry and its fetch forever).
        val entry = withContext(NonCancellable) { reply.await() }
        val value =
            try {
                entry.request.await()
            } finally {
                // trySend never suspends, so the release runs even from here,
                // in a cancelled caller's finally.
                commands.trySend { release(entry) }
            }
        if (value == null) {
            commands.trySend { if (items[id] === entry) items.remove(id) }
        }
        return value
    }

    /** The ids currently held (test seam for the NFR-3 bound; snapshot, not live). */
    suspend fun cachedIds(): Set<String> {
        val reply = CompletableDeferred<Set<String>>()
        check(commands.trySend { reply.complete(items.keys.toSet()) }.isSuccess) { "the cache is closed" }
        return reply.await()
    }

    /**
     * Tear the cache down: no command is accepted afterwards, queued ones and
     * in-flight loads are cancelled. Call once, when nothing else awaits [item].
     */
    fun close() {
        commands.close()
        queueScope.cancel()
        loadScope.cancel()
    }

    /** Runs on the consumer. Creates, and bounds, the entry for [id]. */
    private fun getOrCreate(id: String): Entry<T> {
        items[id]?.let { return it }
        // Bound enforcement (NFR-3): the cache never exceeds the window, so a
        // new entry retires the oldest one in before it goes in.
        while (items.size >= maxEntries) {
            val oldest = items.entries.iterator()
            val evicted = oldest.next()
            oldest.remove()
            retire(evicted.value)
        }
        return Entry(loadScope.async { load(id) }).also { items[id] = it }
    }

    /** Runs on the consumer. Cancel now, or when the last lease releases. */
    private fun retire(entry: Entry<T>?) {
        entry ?: return
        entry.retired = true
        // Never cancel a Deferred an awaiter holds (NFR-2): the last lease's
        // release() does it instead.
        if (entry.leases <= 0) entry.request.cancel()
    }

    /** Runs on the consumer. The last lease of a retired entry kills its load (§6.8). */
    private fun release(entry: Entry<T>) {
        entry.leases--
        if (entry.leases <= 0 && entry.retired) entry.request.cancel()
    }
}
