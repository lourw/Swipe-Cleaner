package dev.swipe.fakes

import dev.swipe.data.MediaLibrary
import dev.swipe.data.compareDatesDescending
import dev.swipe.data.instantInMonth
import dev.swipe.data.isLongVideoDuration
import dev.swipe.data.monthBuckets
import dev.swipe.domain.model.AlbumSource
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DeleteResult
import dev.swipe.domain.model.DuplicateKey
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.model.matches
import dev.swipe.domain.usecase.DuplicateGrouper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.datetime.TimeZone

/**
 * In-memory [MediaLibrary] for tests (§8.2), seeded with [items] and filtered the way
 * PhotoKit will (FR-5, FR-6, FR-7, §6.5):
 *
 * - **Type filter** (FR-5) is ANDed into every source; `PHOTOS` includes Live Photos.
 * - **All / Album / Month / Screenshots / Bursts / Selfies** come back creation-date
 *   descending (null dates last), matching the deck contract of §6.5.
 * - **Long videos** (FR-6): videos of `durationMs >= 60_000`, returned **pre-sorted
 *   duration-descending** — the PhotoKit source uses a duration sort descriptor (§6.5).
 * - **Likely duplicates** (FR-6): the shared `DuplicateGrouper` runs over the same
 *   candidates `duplicateCandidates` returns (id + date + size, newest-first), so only
 *   groups of two or more come back, members next to each other, in both the fake and
 *   the real deck path.
 * - **Album** membership is modeled by [albums] (album id → member asset ids) with
 *   [albumTitles] for row titles; an unknown album is empty. **Selfies** (FR-6) are
 *   modeled by [selfieIds] the same way. [albums] counts and covers reflect current
 *   contents. [months] and month decks bucket in [monthZone] (UTC by default);
 *   [smartCounts] counts the same membership each `assetIds` returns.
 *
 * Calls are recorded (`assetIdsCalls`, `deleteCalls`, …). Each `failNext…` switch throws
 * once. [nextDeleteResult] is returned by every `delete` until changed; on
 * [DeleteResult.Deleted] the assets leave the library, so a later FR-28 prune sees them gone.
 * [access] can be reassigned to simulate a permission change (FR-3 re-check), and
 * [nextRequestAuthorizationResult] lets the prompt answer differently from [access] (FR-1).
 */
class FakeMediaLibrary(
    items: List<MediaItem>,
    /** Album id → member asset ids for [DeckSource.Album] sources (FR-8). */
    private val albums: Map<String, Set<String>> = emptyMap(),
    /** Asset ids PhotoKit would report as selfies (FR-6). */
    private val selfieIds: Set<String> = emptySet(),
    /** What [authorization] reports; reassign to simulate a Settings change (FR-1–3). */
    var access: LibraryAccess = LibraryAccess.FULL,
    /** Album id → row title for [albums]; defaults to the id (FR-8). */
    private val albumTitles: Map<String, String> = emptyMap(),
    /** Calendar zone for month membership and buckets (FR-7); UTC keeps tests deterministic. */
    private val monthZone: TimeZone = TimeZone.UTC,
) : MediaLibrary {
    /** Current library contents; a [DeleteResult.Deleted] removes ids from it (FR-26). */
    val items = items.toMutableList()

    val authorizationCalls = mutableListOf<Unit>()
    val requestAuthorizationCalls = mutableListOf<Unit>()
    val assetIdsCalls = mutableListOf<Pair<DeckSource, MediaTypeFilter>>()
    val itemsCalls = mutableListOf<List<String>>()
    val deleteCalls = mutableListOf<List<String>>()
    val duplicateCandidatesCalls = mutableListOf<MediaTypeFilter>()
    var albumsCalls = 0
        private set
    var monthsCalls = 0
        private set
    var smartCountsCalls = 0
        private set

    var failNextAuthorization: Throwable? = null
    var failNextRequestAuthorization: Throwable? = null
    var failNextAlbums: Throwable? = null
    var failNextMonths: Throwable? = null
    var failNextSmartCounts: Throwable? = null
    var failNextAssetIds: Throwable? = null
    var failNextItems: Throwable? = null
    var failNextDuplicateCandidates: Throwable? = null

    /** Returned by every [delete] until changed; defaults to success. */
    var nextDeleteResult: DeleteResult = DeleteResult.Deleted

    /**
     * When non-null, [delete] records the call and then suspends until this
     * completes — lets tests hold a commit between PhotoKit and markDeleted.
     */
    var deleteGate: CompletableDeferred<Unit>? = null

    /**
     * When non-null, the next [requestAuthorization] answers with this instead of [access],
     * [access] adopts that decision, and the switch resets — models the prompt deciding
     * now (FR-1).
     */
    var nextRequestAuthorizationResult: LibraryAccess? = null

    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    override suspend fun authorization(): LibraryAccess {
        authorizationCalls += Unit
        failNextAuthorization?.let {
            failNextAuthorization = null
            throw it
        }
        return access
    }

    override suspend fun requestAuthorization(): LibraryAccess {
        requestAuthorizationCalls += Unit
        failNextRequestAuthorization?.let {
            failNextRequestAuthorization = null
            throw it
        }
        nextRequestAuthorizationResult?.let {
            nextRequestAuthorizationResult = null
            // The prompt decision is the library's state from now on, as with PhotoKit.
            access = it
            return it
        }
        return access
    }

    override suspend fun albums(): List<AlbumSource> {
        albumsCalls++
        failNextAlbums?.let {
            failNextAlbums = null
            throw it
        }
        // Same shape as the real library: user albums ([albums]) first; counts and
        // cover reflect current contents (deleted items are gone, FR-28).
        return albums.map { (id, memberIds) ->
            val members = items.filter { it.id in memberIds }
            AlbumSource(
                id = id,
                title = albumTitles[id] ?: id,
                count = members.size,
                coverAssetId = members.byDateDescending().firstOrNull()?.id,
            )
        }
    }

    override suspend fun months(type: MediaTypeFilter): List<MonthBucket> {
        monthsCalls++
        failNextMonths?.let {
            failNextMonths = null
            throw it
        }
        // The same commonMain bucketing the PhotoKit library runs (FR-7).
        return monthBuckets(items.filterType(type).map { it.createdAt }, monthZone)
    }

    /**
     * When true, the **next** `smartCounts` call hangs on [releaseSmartCounts]
     * before answering — the NFR-4 seam that proves Home renders the cheap rows
     * while the full-library smart-count pass is still in flight. The gate arms
     * for exactly one call, and tests that never set the flag never touch the
     * deferred, so nothing else can wait on it.
     */
    var gateNextSmartCounts: Boolean = false

    private var smartCountsReleased: CompletableDeferred<Unit>? = null

    /** Let the gated `smartCounts` call (see [gateNextSmartCounts]) answer. */
    fun releaseSmartCounts() {
        smartCountsReleased?.complete(Unit)
        smartCountsReleased = null
    }

    override suspend fun smartCounts(type: MediaTypeFilter): Map<SmartFilter, Int> {
        smartCountsCalls++
        failNextSmartCounts?.let {
            failNextSmartCounts = null
            throw it
        }
        if (gateNextSmartCounts) {
            val gate =
                smartCountsReleased
                    ?: CompletableDeferred<Unit>().also { smartCountsReleased = it }
            gate.await()
            gateNextSmartCounts = false
        }
        return SmartFilter.entries.associateWith { smartItems(it, type).size }
    }

    override suspend fun assetIds(
        source: DeckSource,
        type: MediaTypeFilter,
    ): List<String> {
        assetIdsCalls += source to type
        failNextAssetIds?.let {
            failNextAssetIds = null
            throw it
        }
        return matchingItems(source, type).map { it.id }
    }

    /** Source filter, then the type filter ANDed on top (FR-5), in deck order (§6.5). */
    private fun matchingItems(
        source: DeckSource,
        type: MediaTypeFilter,
    ): List<MediaItem> =
        when (source) {
            DeckSource.All -> {
                items.filterType(type).byDateDescending()
            }

            is DeckSource.Album -> {
                items
                    .filter { it.id in albums[source.id].orEmpty() }
                    .filterType(type)
                    .byDateDescending()
            }

            is DeckSource.Month -> {
                items
                    .filter { it.isInMonth(source.year, source.month) }
                    .filterType(type)
                    .byDateDescending()
            }

            is DeckSource.Smart -> {
                smartItems(source.kind, type)
            }
        }

    private fun smartItems(
        kind: SmartFilter,
        type: MediaTypeFilter,
    ): List<MediaItem> =
        when (kind) {
            SmartFilter.SCREENSHOTS -> {
                items.filter { it.isScreenshot }.filterType(type).byDateDescending()
            }

            SmartFilter.LONG_VIDEOS -> {
                items
                    .filter { it.type == MediaType.VIDEO && isLongVideoDuration(it.durationMs) }
                    .filterType(type)
                    .sortedByDescending { it.durationMs ?: 0L }
            }

            SmartFilter.BURSTS -> {
                items.filter { it.burstId != null }.filterType(type).byDateDescending()
            }

            SmartFilter.LIKELY_DUPLICATES -> {
                // The shared DuplicateGrouper over the same candidates the real library
                // feeds it (FR-6, §6.5), so fake and deck cannot drift.
                val byId = items.associateBy { it.id }
                val candidates =
                    items
                        .filterType(type)
                        .byDateDescending()
                        .map { DuplicateKey(it.id, it.createdAt, it.widthPx, it.heightPx) }
                DuplicateGrouper.group(candidates).mapNotNull { byId[it] }
            }

            SmartFilter.SELFIES -> {
                items.filter { it.id in selfieIds }.filterType(type).byDateDescending()
            }
        }

    private fun List<MediaItem>.filterType(type: MediaTypeFilter): List<MediaItem> = filter { type.matches(it.type) }

    override suspend fun items(ids: List<String>): List<MediaItem> {
        itemsCalls += ids
        failNextItems?.let {
            failNextItems = null
            throw it
        }
        // Keep the input order; ids the library no longer has are dropped (FR-28).
        val byId = items.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    override suspend fun delete(ids: List<String>): DeleteResult {
        deleteCalls += ids
        deleteGate?.await()
        val result = nextDeleteResult
        if (result == DeleteResult.Deleted) {
            items.removeAll { it.id in ids }
        }
        return result
    }

    override fun changes(): Flow<Unit> = changesFlow

    override suspend fun duplicateCandidates(type: MediaTypeFilter): List<DuplicateKey> {
        duplicateCandidatesCalls += type
        failNextDuplicateCandidates?.let {
            failNextDuplicateCandidates = null
            throw it
        }
        return items
            .filter { type.matches(it.type) }
            .byDateDescending()
            .map { DuplicateKey(it.id, it.createdAt, it.widthPx, it.heightPx) }
    }

    /** Make [changes] emit, as a change in the Photos app would (FR-28). */
    fun notifyChange() {
        changesFlow.tryEmit(Unit)
    }

    /** FR-7: month membership via the shared helper, in [monthZone]; undated items are in no month. */
    private fun MediaItem.isInMonth(
        year: Int,
        month: Int,
    ): Boolean = instantInMonth(createdAt, year, month, monthZone)

    /** Creation date descending; items with no date keep seed order at the end (§6.5). */
    private fun List<MediaItem>.byDateDescending(): List<MediaItem> =
        sortedWith { a, b -> compareDatesDescending(a.createdAt, b.createdAt) }
}
