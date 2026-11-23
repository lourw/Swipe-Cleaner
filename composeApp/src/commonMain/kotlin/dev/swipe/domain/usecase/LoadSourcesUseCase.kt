package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.AlbumSource
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * A Home snapshot from [LoadSourcesUseCase] (FR-4, §6.5 *Home counts*). Each field
 * carries its placeholder → exact ladder (§6.5), and every emission is a step of one
 * load, so consumers keep whatever has not arrived yet:
 *
 * - [shuffleCount]: the raw `assetIds(All, type)` size first — an ids-only fetch
 *   (NFR-4) — then the exact FR-9 count once decided ids are subtracted; null until
 *   the fetch answers.
 * - [smartCounts]: only the filters whose count has arrived are present; the value
 *   is PhotoKit's raw count first, then the exact FR-9 count (see the class doc for
 *   the Bursts / Likely Duplicates pass-2 note). A missing key is the placeholder.
 * - [albums]: the [AlbumSource] rows first with PhotoKit's cheap estimate counts
 *   (FR-8, NFR-4), each row converging to the exact FR-9 count for the current
 *   filter as it is refined; null until the fetch answers.
 *
 * The `*xact*` fields tag which values are already the FR-9-exact count, so a
 * consumer re-running this flow (a `changes()` refresh) can keep its refined
 * numbers on screen while the new run's raw estimates stream in (§6.5).
 */
data class SourceCounts(
    val shuffleCount: Int? = null,
    /** True once [shuffleCount] is the FR-9-exact count, not the raw fetch size. */
    val shuffleExact: Boolean = false,
    val smartCounts: Map<SmartFilter, Int> = emptyMap(),
    /** Filters whose [smartCounts] value is FR-9-exact. */
    val exactSmart: Set<SmartFilter> = emptySet(),
    val albums: List<AlbumSource>? = null,
    /** Album ids whose [albums] row count is FR-9-exact. */
    val exactAlbumIds: Set<String> = emptySet(),
)

/**
 * Loads the Home row counts — the **Shuffle everything** count, the five
 * **Smart filters** counts, and the **Albums** rows — for one media-type filter
 * (FR-4, FR-5, FR-9, FR-10; §6.5 *Home counts*).
 *
 * The returned Flow emits progressively so Home is interactive before the counts
 * land (NFR-4): the cheap fetches come first — `albums()` (estimates + covers, no
 * enumeration), the ids-only `assetIds(All, type)`, then `smartCounts(type)`, whose
 * Bursts and Likely Duplicates entries each cost a full-library pass (§6.4) — and
 * every count starts as a placeholder until its source answers.
 *
 * **FR-9 exclusion** runs afterwards, lazily, row by row: `decidedIds()` is read
 * once, then each row is counted with the deck's own path minus the decided set,
 * one emission per refinement wave whose number actually moves. Likely Duplicates
 * refines through [MediaLibrary.duplicateCandidates] + [DuplicateGrouper] — decided
 * subtracted **before** grouping, exactly like [BuildDeckUseCase] — because a twin
 * whose partner is decided leaves the deck, and membership-minus-decided would
 * over-report it. Bursts therefore pay a second full-library pass *after* the
 * placeholder is already on screen (§6.5); a row whose refinement fetch fails keeps
 * its raw count, and the next `changes()` refresh retries.
 *
 * A failed fetch never kills the Flow — the row simply keeps its placeholder until
 * the next refresh (the caller re-runs this use case on every
 * [MediaLibrary.changes()] emission, §6.5 / §6.8).
 */
class LoadSourcesUseCase(
    private val library: MediaLibrary,
    private val decisions: DecisionStore,
    /** The deck's own membership path (FR-9, FR-37): every exact count below is the deck's size. */
    private val membership: DeckMembership,
) {
    /** The progressive count snapshots for [type] (FR-5: the filter is ANDed into every source). */
    operator fun invoke(type: MediaTypeFilter): Flow<SourceCounts> =
        flow {
            var counts = SourceCounts()

            // 1. Albums (FR-8, NFR-4): cheap estimatedAssetCount + cover, no enumeration.
            val albums = attempt { library.albums() }
            if (albums != null) {
                counts = counts.copy(albums = albums)
                emit(counts)
            }

            // 2. Shuffle count (FR-4): ids-only fetch, kept for the FR-9 refinement.
            val allIds = attempt { library.assetIds(DeckSource.All, type) }
            if (allIds != null) {
                counts = counts.copy(shuffleCount = allIds.size)
                emit(counts)
            }

            // 3. Smart counts (FR-6): PhotoKit's raw membership counts; Bursts and
            //    Likely Duplicates each cost a full pass inside the library, which is
            //    exactly why the placeholders above came first (NFR-4).
            val smart = attempt { library.smartCounts(type) }
            if (smart != null) {
                counts = counts.copy(smartCounts = smart)
                emit(counts)
            }

            // 4. FR-9: subtract decided ids per row, lazily (§6.5 *Home counts*).
            val decided = attempt { decisions.decidedIds() }.orEmpty()
            refineRows(counts, allIds, type, decided)
        }

    /**
     * Phase 4 of [invoke]: count each non-empty row with the deck's own path minus
     * the decided set and emit the result, one emission per refinement wave whose
     * number actually moves — a refinement that agrees with the placeholder is not
     * a new screen state (NFR-4). Album estimates always converge, even with
     * nothing decided: the estimate ignores [type] and can even report 0 for a
     * non-empty album (Apple documents `estimatedAssetCount` as approximate).
     */
    private suspend fun FlowCollector<SourceCounts>.refineRows(
        counts: SourceCounts,
        allIds: List<String>?,
        type: MediaTypeFilter,
        decided: Set<String>,
    ) {
        var current = refineShuffle(counts, allIds, type, decided)
        current = refineSmart(current, type, decided)
        refineAlbums(current, type, decided)
    }

    /**
     * FR-9-exact Shuffle count: [DeckMembership.ids] for [DeckSource.All] —
     * decided subtracted and FR-37 exclusions applied — so the row shows the
     * size of the very deck it opens (FR-9).
     *
     * The refinement **always announces itself once per run**, even when its
     * number equals the raw estimate: the Shuffle row is the one row whose
     * refinement moves while the raw count stands still — FR-37 exclusions
     * change the deck, not the source — and [HomeViewModel]'s exactness mask
     * holds a refined number "until the re-run's own refinement replaces it"
     * (§6.5). A refinement suppressed as equal would pin the old number
     * forever; the announcement itself never moves a displayed number, so it
     * cannot flicker the row (NFR-4). A failed refinement stays silent: the
     * row keeps its number and the next refresh retries it (§6.8).
     */
    private suspend fun FlowCollector<SourceCounts>.refineShuffle(
        counts: SourceCounts,
        allIds: List<String>?,
        type: MediaTypeFilter,
        decided: Set<String>,
    ): SourceCounts {
        val refined =
            if (allIds != null && counts.shuffleCount != null) {
                attempt { membership.ids(DeckSource.All, type, decided).size }
            } else {
                null
            }
        if (refined == null) return counts
        val current = counts.copy(shuffleCount = refined, shuffleExact = true)
        emit(current)
        return current
    }

    /**
     * FR-9-exact smart counts, one emission per row whose number moves. A raw
     * count of 0 means the source is empty, so the exact count is 0 too — no
     * second full pass to buy for it (§6.5).
     */
    private suspend fun FlowCollector<SourceCounts>.refineSmart(
        counts: SourceCounts,
        type: MediaTypeFilter,
        decided: Set<String>,
    ): SourceCounts {
        var current = counts
        for (kind in SmartFilter.entries) {
            val raw = current.smartCounts[kind]
            if (raw != null && raw > 0) {
                val refined = attempt { exactSmartCount(kind, type, decided) }
                if (refined != null) {
                    val moved = current.smartCounts[kind] != refined
                    current =
                        current.copy(
                            smartCounts = current.smartCounts + (kind to refined),
                            exactSmart = current.exactSmart + kind,
                        )
                    if (moved) emit(current)
                }
            }
        }
        return current
    }

    /**
     * FR-9 + FR-5-exact album counts, fetched even at a 0 estimate — only the
     * fetch proves membership for [type] (§6.5) — emitted as one batch when any
     * row moves; a failed fetch keeps that row's estimate for the next refresh.
     */
    private suspend fun FlowCollector<SourceCounts>.refineAlbums(
        counts: SourceCounts,
        type: MediaTypeFilter,
        decided: Set<String>,
    ): SourceCounts {
        var current = counts
        val albumsNow = counts.albums.orEmpty()
        if (albumsNow.isNotEmpty()) {
            var moved = false
            val exactAlbumIds = counts.exactAlbumIds.toMutableSet()
            val refinedAlbums =
                albumsNow.map { album ->
                    // The deck's own path for the row (FR-9): an album deck never
                    // applies the FR-37 exclusions, so opening it keeps its items.
                    val count = attempt { membership.ids(DeckSource.Album(album.id, album.title), type, decided).size }
                    if (count == null) {
                        album
                    } else {
                        exactAlbumIds += album.id
                        album.copy(count = count).also { if (it != album) moved = true }
                    }
                }
            if (moved) {
                current = counts.copy(albums = refinedAlbums, exactAlbumIds = exactAlbumIds)
                emit(current)
            }
        }
        return current
    }

    /**
     * One row's FR-9-exact count via the deck's own path: Likely Duplicates
     * subtracts [decided] **before** grouping (like [BuildDeckUseCase]), every
     * other smart filter is ids-minus-decided.
     */
    private suspend fun exactSmartCount(
        kind: SmartFilter,
        type: MediaTypeFilter,
        decided: Set<String>,
    ): Int =
        if (kind == SmartFilter.LIKELY_DUPLICATES) {
            membership.duplicateDeckIds(type, decided).size
        } else {
            membership.ids(DeckSource.Smart(kind), type, decided).size
        }

    /**
     * Runs one library read; a failed read returns null so its row keeps its
     * placeholder and the next `changes()` refresh retries it (§6.8). Cancellation
     * still propagates — teardown is not a failed fetch.
     */
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation // flow teardown, not a failed fetch
        } catch (_: Exception) {
            null
        }
}
