package dev.swipe.fakes

import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DeleteResult
import dev.swipe.domain.model.DuplicateKey
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.model.matches
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * In-memory [MediaLibrary] for tests (§8.2), seeded with [items] and filtered the way
 * PhotoKit will (FR-5, FR-6, FR-7, §6.5):
 *
 * - **Type filter** (FR-5) is ANDed into every source; `PHOTOS` includes Live Photos.
 * - **All / Album / Month / Screenshots / Bursts / Selfies** come back creation-date
 *   descending (null dates last), matching the deck contract of §6.5.
 * - **Long videos** (FR-6): videos of `durationMs >= 60_000`, returned **pre-sorted
 *   duration-descending** — the PhotoKit source uses a duration sort descriptor (§6.5).
 * - **Likely duplicates** (FR-6): candidates walked newest-first, run together when
 *   neighbours share pixel dimensions and are within 2 s of each other; only groups of
 *   two or more come back, members next to each other. `duplicateCandidates` (FR-6)
 *   returns the same candidates' keys, sorted by creation date, for `DuplicateGrouper`.
 * - **Album** membership is modeled by [albums] (album id → member asset ids); an unknown
 *   album is empty. **Selfies** (FR-6) are modeled by [selfieIds] the same way.
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
) : MediaLibrary {
    /** Current library contents; a [DeleteResult.Deleted] removes ids from it (FR-26). */
    val items = items.toMutableList()

    val authorizationCalls = mutableListOf<Unit>()
    val requestAuthorizationCalls = mutableListOf<Unit>()
    val assetIdsCalls = mutableListOf<Pair<DeckSource, MediaTypeFilter>>()
    val itemsCalls = mutableListOf<List<String>>()
    val deleteCalls = mutableListOf<List<String>>()
    val duplicateCandidatesCalls = mutableListOf<MediaTypeFilter>()
    var sourcesCalls = 0
        private set

    var failNextAuthorization: Throwable? = null
    var failNextRequestAuthorization: Throwable? = null
    var failNextSources: Throwable? = null
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

    override suspend fun sources(): List<DeckSource> {
        sourcesCalls++
        failNextSources?.let {
            failNextSources = null
            throw it
        }
        return listOf(DeckSource.All)
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
                    .filter { it.type == MediaType.VIDEO && (it.durationMs ?: 0L) >= LONG_VIDEO_MIN_MS }
                    .filterType(type)
                    .sortedByDescending { it.durationMs ?: 0L }
            }

            SmartFilter.BURSTS -> {
                items.filter { it.burstId != null }.filterType(type).byDateDescending()
            }

            SmartFilter.LIKELY_DUPLICATES -> {
                duplicateGroups(items.filterType(type))
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

    /** FR-7: month buckets are UTC; items with no date are in no month. */
    private fun MediaItem.isInMonth(
        year: Int,
        month: Int,
    ): Boolean {
        val instant = createdAt ?: return false
        val local = instant.toLocalDateTime(TimeZone.UTC)
        return local.year == year && local.month.ordinal + 1 == month
    }

    /** Creation date descending; items with no date keep seed order at the end (§6.5). */
    private fun List<MediaItem>.byDateDescending(): List<MediaItem> =
        sortedWith { a, b ->
            when {
                a.createdAt == null && b.createdAt == null -> 0
                a.createdAt == null -> 1
                b.createdAt == null -> -1
                else -> b.createdAt.compareTo(a.createdAt)
            }
        }

    /** FR-6 grouping: equal dimensions and neighbours within 2 s; groups of >= 2 only. */
    private fun duplicateGroups(candidates: List<MediaItem>): List<MediaItem> {
        val sorted = candidates.filter { it.createdAt != null }.byDateDescending()
        val result = mutableListOf<MediaItem>()
        var group = mutableListOf<MediaItem>()

        fun flush() {
            if (group.size >= 2) {
                result += group
            }
            group = mutableListOf()
        }
        for (item in sorted) {
            val previous = group.lastOrNull()
            val joins =
                previous != null &&
                    previous.widthPx == item.widthPx &&
                    previous.heightPx == item.heightPx &&
                    previous.createdAt!!.minus(item.createdAt!!).inWholeMilliseconds <= DUPLICATE_WINDOW_MS
            if (previous != null && !joins) {
                flush()
            }
            group += item
        }
        flush()
        return result
    }

    private companion object {
        /** FR-6: long videos are >= 60 s. */
        const val LONG_VIDEO_MIN_MS = 60_000L

        /** FR-6: likely duplicates are taken within 2 s of each other. */
        const val DUPLICATE_WINDOW_MS = 2_000L
    }
}
