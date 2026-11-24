package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DuplicateSetMembership
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter

/**
 * The ordered asset ids a deck shows, plus the FR-35 duplicate-set memberships
 * for the cards that sit in a likely duplicate set — empty for every other
 * source, which shows no label (§6.6).
 */
data class BuiltDeck(
    val ids: List<String>,
    val duplicateSets: Map<String, DuplicateSetMembership> = emptyMap(),
)

/**
 * Builds the deck of asset ids for one review session (REQUIREMENTS §6.5):
 * the source's membership through [DeckMembership] — a Keep or a pending Delete
 * never comes back (FR-9), and for [DeckSource.All] the items of any album the
 * user excluded from Shuffle are already out (FR-37) — then ordered per source:
 *
 * - [DeckSource.All] is ordered by a seeded per-id key ([stableShuffleOrder]),
 *   so the same seed always reproduces the order (the seed lives on the session
 *   row, §6.5 step 1) and items entering or leaving the library never move the
 *   others (FR-20).
 * - `Smart(LONG_VIDEOS)` keeps the library order, which is pre-sorted
 *   duration-descending by a PhotoKit sort descriptor (FR-6, §6.5).
 * - `Smart(LIKELY_DUPLICATES)` comes from
 *   [DeckMembership.duplicateDeck] — decided items subtracted before
 *   grouping so runs stay honest (FR-6), and the FR-35 set memberships come
 *   from the same single candidate pass.
 * - Everything else keeps the library order, creation date descending (§6.5).
 */
class BuildDeckUseCase(
    private val membership: DeckMembership,
    private val decisions: DecisionStore,
) {
    /**
     * The deck for [source] narrowed by [typeFilter] (FR-5). [seed] drives the
     * order of [DeckSource.All] and is ignored by every other source. The order
     * is a per-id key sort, so it survives membership changes by construction:
     * a rebuild with the same seed lands on the same order minus whatever has
     * been decided or excluded since, which is what resume needs to reopen a
     * session on the same card (FR-20).
     */
    suspend operator fun invoke(
        source: DeckSource,
        typeFilter: MediaTypeFilter,
        seed: Long,
    ): BuiltDeck {
        val decided = decisions.decidedIds()
        if (source.isLikelyDuplicates) {
            return membership.duplicateDeck(typeFilter, decided)
        }
        val ids = membership.ids(source, typeFilter, decided)
        return if (source == DeckSource.All) {
            BuiltDeck(stableShuffleOrder(ids, seed))
        } else {
            BuiltDeck(ids)
        }
    }

    private val DeckSource.isLikelyDuplicates: Boolean
        get() = this is DeckSource.Smart && kind == SmartFilter.LIKELY_DUPLICATES
}

/**
 * The FR-20 Shuffle order: ids sorted by [shuffleKey], the seed-mixed hash of
 * each id, with the id string itself breaking the rare key tie so the result is
 * total and never depends on fetch order (§6.5; WP-7.7 made the fetches
 * sorted, but Shuffle must not rely on that). Because the key is a property of (seed, id) alone, filtering the list
 * — decided items (FR-9), FR-37 exclusions, a library that grew or shrank
 * between sessions — commutes with the sort: surviving ids keep their relative
 * order, where a positional `shuffled(Random(seed))` permutation would move
 * every card after the first insertion or removal.
 */
internal fun stableShuffleOrder(
    ids: List<String>,
    seed: Long,
): List<String> = ids.sortedWith(compareBy({ shuffleKey(it, seed) }, { it }))

/**
 * The per-id sort key: a splitmix64 finalizer over `seed xor id.hashCode()`,
 * deterministic across launches and platforms (`String.hashCode` is specified).
 * The avalanche spreads both inputs over all 64 bits, so neighbouring ids and
 * neighbouring seeds both produce unrelated orders.
 */
private fun shuffleKey(
    id: String,
    seed: Long,
): Long {
    var z = (seed.toULong() xor id.hashCode().toULong()) + GOLDEN_RATIO
    z = (z xor (z shr MIX_SHIFT_1)) * MIX_A
    z = (z xor (z shr MIX_SHIFT_2)) * MIX_B
    return (z xor (z shr MIX_SHIFT_3)).toLong()
}

// The splitmix64 constants (the same ones the PRNG family uses); ULong so the
// literals fit, with plain wrapping 64-bit arithmetic.
private const val GOLDEN_RATIO = 0x9E3779B97F4A7C15uL
private const val MIX_A = 0xBF58476D1CE4E5B9uL
private const val MIX_B = 0x94D049BB133111EBuL
private const val MIX_SHIFT_1 = 30
private const val MIX_SHIFT_2 = 27
private const val MIX_SHIFT_3 = 31
