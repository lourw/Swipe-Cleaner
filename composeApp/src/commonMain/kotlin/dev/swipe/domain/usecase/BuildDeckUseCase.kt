package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import kotlin.random.Random

/**
 * Builds the deck of asset ids for one review session (REQUIREMENTS §6.5):
 * the source's ids with every already-decided item subtracted — a Keep or a pending
 * Delete never comes back (FR-9) — then ordered per source:
 *
 * - [DeckSource.All] is shuffled with `Random(seed)`, so the same seed always
 *   reproduces the order (the seed lives on the session row, §6.5 step 1).
 * - `Smart(LONG_VIDEOS)` keeps the library order, which is pre-sorted
 *   duration-descending by a PhotoKit sort descriptor (FR-6, §6.5).
 * - `Smart(LIKELY_DUPLICATES)` comes from
 *   [MediaLibrary.duplicateCandidates] through [DuplicateGrouper], decided items
 *   subtracted before grouping so runs stay honest (FR-6).
 * - Everything else keeps the library order, creation date descending (§6.5).
 */
class BuildDeckUseCase(
    private val library: MediaLibrary,
    private val decisions: DecisionStore,
) {
    /**
     * The deck for [source] narrowed by [typeFilter] (FR-5). [seed] drives the shuffle
     * of [DeckSource.All] and is ignored by every other source. [DeckSource.All]
     * shuffles the *full* id list before decided ids are subtracted, so the seeded
     * order survives decisions: a rebuild with the same seed lands on the same order
     * minus the decided items, which is what resume needs to reopen a session on the
     * same card (FR-20). Other sources subtract before ordering.
     */
    suspend operator fun invoke(
        source: DeckSource,
        typeFilter: MediaTypeFilter,
        seed: Long,
    ): List<String> {
        val decided = decisions.decidedIds()
        if (source.isLikelyDuplicates) {
            val candidates =
                library
                    .duplicateCandidates(typeFilter)
                    .filterNot { it.id in decided }
            return DuplicateGrouper.group(candidates)
        }
        val ids = library.assetIds(source, typeFilter)
        // Shuffle first, subtract second (see the doc): filtering first would change
        // the permutation as rows accumulate and break resume's order (FR-20).
        val ordered = if (source == DeckSource.All) ids.shuffled(Random(seed)) else ids
        return ordered.filterNot { it in decided }
    }

    private val DeckSource.isLikelyDuplicates: Boolean
        get() = this is DeckSource.Smart && kind == SmartFilter.LIKELY_DUPLICATES
}
