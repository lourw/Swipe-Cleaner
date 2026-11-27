package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.AlbumSource
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket
import dev.swipe.domain.model.SmartFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Home row counts from one [SourceCountsUseCase] read (FR-4, §6.5 *Home
 * counts*). Each field is **final or absent**: a field that carries a value
 * carries the FR-9-exact deck size — the raw PhotoKit estimate stage is gone
 * (WP-8.12), so a row never shows a number that is about to be replaced by a
 * different one. Consumers keep whatever has not arrived yet:
 *
 * - [shuffleCount]: the exact Shuffle deck size (`DeckMembership.ids(All, type)`
 *   minus decided, minus FR-37 excluded items); null until the count answers.
 * - [smartCounts]: only the filters whose exact count has arrived are present;
 *   a missing key is the placeholder. Likely Duplicates counts the deck's own
 *   grouped path ([DeckMembership.duplicateDeckIds]).
 * - [albums]: the [AlbumSource] identity and covers (titles, cover ids); the
 *   rows' counts are **not** in [AlbumSource.count] — the library's estimate is
 *   never shown — they arrive in [albumCounts] as each row's exact count lands.
 *
 * An absent value never overwrites: the consumer keeps its placeholder (before
 * the first arrival) or its held final number (across a `changes()` re-run),
 * so a refresh updates a row once per real change and never dips it back
 * (§6.5).
 */
data class SourceCounts(
    /** The FR-9-exact Shuffle deck size; null while loading. */
    val shuffleCount: Int? = null,
    /** FR-9-exact smart counts, sparse: only the rows whose count has landed. */
    val smartCounts: Map<SmartFilter, Int> = emptyMap(),
    /** Album rows (identity + cover) for the **Albums** row list; null until the fetch answers. */
    val albums: List<AlbumSource>? = null,
    /** Exact FR-9 album row counts keyed by album id, sparse: only the rows whose count has landed. */
    val albumCounts: Map<String, Int> = emptyMap(),
)

/**
 * One snapshot of a counted row list (Albums, By month): [rows] in display
 * order with identity and covers, and [counts] — the exact FR-9 counts that
 * have landed, keyed by [key]. A row missing from [counts] shows the
 * placeholder; a count that has landed is final for this run (§6.5).
 */
data class CountedRows<T>(
    val rows: List<T>,
    val counts: Map<String, Int>,
) {
    /** The count for the row with [key], or null while it is still the placeholder. */
    fun countFor(key: String): Int? = counts[key]
}

/**
 * **The one count path** (FR-9, WP-8.12, §6.5 *Home counts*): every count the
 * app shows — Home's Shuffle and Smart rows, the Albums rows, and the By month
 * buckets — is computed here, and every count is the size of the very deck its
 * row opens, through [DeckMembership.ids] (Likely Duplicates through
 * [DeckMembership.duplicateDeckIds]). There is no second, cheaper counting
 * query that could disagree with the deck.
 *
 * **Final-or-placeholder, never intermediate (FR-5).** A row shows the
 * placeholder until its exact count is ready; PhotoKit's raw fetch size and
 * `estimatedAssetCount` are never emitted as counts (they ignore the type
 * filter, include decided items, and can lag reality — the source of the
 * jump-to-the-total flicker on a type switch). The flows still arrive
 * progressively so screens mount and fill in without waiting (NFR-4): row
 * identity and covers first (the cheap `albums()` / `months()` reads), then
 * one emission per row as its exact count lands.
 *
 * A failed fetch never kills a Flow: that row keeps its placeholder (or its
 * held number across a refresh) and the caller's next `changes()` re-run
 * retries it (§6.8). The one exception is the row-list reads' **initial
 * scan** ([albums] / [months] on the library): a failed scan is thrown so the
 * screen can explain itself (WP-9.6, FR-7, FR-8).
 */
class SourceCountsUseCase(
    private val library: MediaLibrary,
    private val decisions: DecisionStore,
    /** The deck's own membership path (FR-9, FR-37): every count here is the deck's size. */
    private val membership: DeckMembership,
) {
    /**
     * The progressive Home counts for [type] (FR-4, FR-5, FR-9): the Albums
     * row identity first (cheap, no enumeration — NFR-4), then the exact
     * Shuffle count, then the five smart rows one by one, then the album rows
     * one by one. The full-library passes (Bursts, Likely Duplicates) sit
     * behind their own row, not in front of the cheap ones (§6.5). A refresh
     * makes exactly one Bursts id pass and one duplicate-candidate pass
     * (NFR-4, pinned by `SourceCountsUseCaseTest`).
     */
    operator fun invoke(type: MediaTypeFilter): Flow<SourceCounts> =
        flow {
            var counts = SourceCounts()

            // 1. Albums row identity (FR-8): titles + covers, no enumeration.
            //    The library's estimate rides along in AlbumSource.count but
            //    is never displayed — counts arrive only through albumCounts.
            val albums = attempt { library.albums() }
            if (albums != null) {
                counts = counts.copy(albums = albums)
                emit(counts)
            }

            val decided = attempt { decisions.decidedIds() }.orEmpty()

            // 2. Shuffle (FR-4): the size of the deck the row opens — decided
            //    subtracted, FR-37 exclusions applied (FR-9, FR-37).
            val shuffle = attempt { membership.ids(DeckSource.All, type, decided).size }
            if (shuffle != null) {
                counts = counts.copy(shuffleCount = shuffle)
                emit(counts)
            }

            // 3. Smart rows (FR-6), one emission per row as its count lands.
            for (kind in SmartFilter.entries) {
                val count = attempt { exactSmartCount(kind, type, decided) }
                if (count != null) {
                    counts = counts.copy(smartCounts = counts.smartCounts + (kind to count))
                    emit(counts)
                }
            }

            // 4. Album rows (FR-8), one emission per row as its count lands.
            albums?.forEach { album ->
                val count = attempt { exactAlbumCount(album, type, decided) }
                if (count != null) {
                    counts = counts.copy(albumCounts = counts.albumCounts + (album.id to count))
                    emit(counts)
                }
            }
        }

    /**
     * The Albums screen's rows for [type] (FR-8, FR-9): identity and covers
     * first, then each row's exact count, one emission per row. The same
     * album path Home's own flow uses, so both screens' numbers come from one
     * place (WP-8.12). A failed **scan** is thrown, not swallowed: the screen
     * owns the explanation (WP-9.6, FR-8) — only the per-row count reads
     * stay silent, keeping their placeholders (§6.8).
     */
    fun albums(type: MediaTypeFilter): Flow<CountedRows<AlbumSource>> =
        flow {
            val albums = library.albums()
            emit(CountedRows(albums, emptyMap()))
            val decided = attempt { decisions.decidedIds() }.orEmpty()
            var counts: Map<String, Int> = emptyMap()
            albums.forEach { album ->
                val count = attempt { exactAlbumCount(album, type, decided) }
                if (count != null) {
                    counts += album.id to count
                    emit(CountedRows(albums, counts))
                }
            }
        }

    /**
     * The By month buckets for [type] (FR-7, FR-9): the bucket list from the
     * lazy `months(type)` scan first (counts still placeholders), then each
     * bucket's exact count — the size of the `DeckSource.Month` deck the row
     * opens, decided items subtracted (FR-9). Before WP-8.12 this screen
     * showed the raw scan count, which over-reported every bucket that held a
     * decided item. A failed **scan** is thrown, not swallowed: the screen
     * owns the explanation (WP-9.6, FR-7) — only the per-row count reads stay
     * silent, keeping their placeholders (§6.8).
     */
    fun months(type: MediaTypeFilter): Flow<CountedRows<MonthBucket>> =
        flow {
            val buckets = library.months(type)
            emit(CountedRows(buckets, emptyMap()))
            val decided = attempt { decisions.decidedIds() }.orEmpty()
            var counts: Map<String, Int> = emptyMap()
            buckets.forEach { bucket ->
                val count =
                    attempt {
                        membership.ids(DeckSource.Month(bucket.year, bucket.month), type, decided).size
                    }
                if (count != null) {
                    counts += monthKey(bucket) to count
                    emit(CountedRows(buckets, counts))
                }
            }
        }

    /** The [CountedRows] key for a month bucket. */
    companion object {
        fun monthKey(bucket: MonthBucket): String = "${bucket.year}-${bucket.month}"
    }

    /**
     * One row's FR-9-exact smart count via the deck's own path: Likely
     * Duplicates subtracts [decided] **before** grouping (like
     * [BuildDeckUseCase]), every other smart filter is ids-minus-decided.
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
     * One row's FR-9-exact album count: the album deck's own path (FR-9) — an
     * album deck never applies the FR-37 exclusions, so opening it keeps its
     * items — for [type] (FR-5), decided subtracted.
     */
    private suspend fun exactAlbumCount(
        album: AlbumSource,
        type: MediaTypeFilter,
        decided: Set<String>,
    ): Int = membership.ids(DeckSource.Album(album.id, album.title), type, decided).size

    /**
     * Runs one library read; a failed read returns null so its row keeps its
     * placeholder and the next `changes()` refresh retries it (§6.8).
     * Cancellation still propagates — teardown is not a failed fetch.
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
