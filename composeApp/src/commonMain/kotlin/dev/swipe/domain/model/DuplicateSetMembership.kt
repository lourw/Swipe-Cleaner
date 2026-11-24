package dev.swipe.domain.model

/**
 * Where a card sits inside its likely duplicate set (FR-35), computed from the
 * **unfiltered** candidates so a resumed set still reads its full size (§6.5).
 *
 * [memberIds] is the whole set in the duplicate pass' order (newest first);
 * [position] is the card's 0-based index in that order, so the position-0
 * member is the one the set shows first and the label renders
 * "position + 1 of memberIds.size in this set". A member decided away remains
 * in [memberIds] — the set is what it is, whatever the user has decided.
 */
data class DuplicateSetMembership(
    val memberIds: List<String>,
    val position: Int,
)
