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
 */
data class SourceCounts(
    val shuffleCount: Int? = null,
    val smartCounts: Map<SmartFilter, Int> = emptyMap(),
    val albums: List<AlbumSource>? = null,
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
 * once, then each row's own membership is re-fetched with the same ids-only
 * `assetIds(source, type)` the deck would run and counted minus the decided set,
 * one emission per refined row. That makes every count exact and equal to the deck
 * size the row opens. Bursts and Likely Duplicates therefore pay a second
 * full-library pass *after* the placeholder is already on screen (§6.5); a row
 * whose refinement fetch fails keeps its raw count, and the next `changes()`
 * refresh retries.
 *
 * A failed fetch never kills the Flow — the row simply keeps its placeholder until
 * the next refresh (the caller re-runs this use case on every
 * [MediaLibrary.changes()] emission, §6.5 / §6.8).
 */
class LoadSourcesUseCase(
    private val library: MediaLibrary,
    private val decisions: DecisionStore,
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
     * Phase 4 of [invoke]: re-fetch each non-empty row with the deck's own
     * `assetIds(source, type)` and emit the membership-minus-decided count, one
     * emission per row whose number actually moves — a refinement that agrees
     * with the placeholder is not a new screen state (NFR-4). Album estimates
     * converge even with nothing decided, because the estimate ignores [type].
     */
    private suspend fun FlowCollector<SourceCounts>.refineRows(
        counts: SourceCounts,
        allIds: List<String>?,
        type: MediaTypeFilter,
        decided: Set<String>,
    ) {
        var current = counts
        if (allIds != null && current.shuffleCount != null) {
            val refined = exact(allIds, decided)
            if (current.shuffleCount != refined) {
                current = current.copy(shuffleCount = refined)
                emit(current)
            }
        }
        for (kind in SmartFilter.entries) {
            // A raw count of 0 means the source is empty, so the exact count is 0
            // too — no second full pass to buy for it (§6.5).
            val raw = current.smartCounts[kind]
            if (raw != null && raw > 0) {
                val ids = attempt { library.assetIds(DeckSource.Smart(kind), type) }
                if (ids != null) {
                    val refined = exact(ids, decided)
                    if (current.smartCounts[kind] != refined) {
                        current = current.copy(smartCounts = current.smartCounts + (kind to refined))
                        emit(current)
                    }
                }
            }
        }
        val albumsNow = current.albums
        if (albumsNow != null && albumsNow.isNotEmpty()) {
            val refinedAlbums = albumsNow.map { album -> refineAlbum(album, type, decided) }
            if (refinedAlbums != albumsNow) {
                current = current.copy(albums = refinedAlbums)
                emit(current)
            }
        }
    }

    /** One album row's FR-9 + FR-5 refinement; an empty album stays empty, a failed fetch keeps the estimate. */
    private suspend fun refineAlbum(
        album: AlbumSource,
        type: MediaTypeFilter,
        decided: Set<String>,
    ): AlbumSource {
        // An empty album is exact under every filter and every decision; a failed
        // fetch keeps the estimate for the next refresh to retry (§6.8).
        val ids =
            if (album.count == 0) {
                null
            } else {
                attempt { library.assetIds(DeckSource.Album(album.id, album.title), type) }
            }
        return if (ids == null) album else album.copy(count = exact(ids, decided))
    }

    /** Membership minus decided ids — what FR-9 asks of every source count. */
    private fun exact(
        ids: List<String>,
        decided: Set<String>,
    ): Int = ids.count { it !in decided }

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
