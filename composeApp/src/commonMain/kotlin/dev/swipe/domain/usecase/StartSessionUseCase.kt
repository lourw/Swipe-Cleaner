package dev.swipe.domain.usecase

import dev.swipe.data.DecisionStore
import dev.swipe.data.SessionStore
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DuplicateSetMembership
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SessionRecord

/**
 * What [StartSessionUseCase] settles so a deck can open: the session row in force,
 * the deck ids to review, and how many cards were disposed before this deck was
 * built (FR-19 — after a resume they are out of the rebuilt deck but still count
 * toward `reviewed`, so `reviewed = reviewedBase + index`).
 */
data class SessionStart(
    val sessionId: String,
    val deck: List<String>,
    val reviewedBase: Int,
    /**
     * FR-35: place-in-set info for the Likely Duplicates deck, empty for every
     * other source; built with the deck in the same pass (§6.5).
     */
    val duplicateSets: Map<String, DuplicateSetMembership> = emptyMap(),
)

/**
 * §6.5 step 1: resume the open session or start a fresh one, and hand back the
 * deck to show. Extracted from `SwipeViewModel` by WP-9.2; failures propagate —
 * the caller treats the whole start path as retriable by reopening.
 *
 * Resume (FR-20): a [SessionStore.openSession] row whose filter matches and whose
 * source matches this one resumes that session — the stored seed rebuilds the same
 * shuffle order (it survives process death), the stored keeps and the session's
 * skips stay excluded (FR-9, FR-14) so the screen reopens on the same card, and
 * the stored counts become the [SessionStart.reviewedBase]. Album sources match on
 * the album **id only**: renaming the album leaves the row's stored title behind,
 * and a rename must not strand the session (WP-9.2).
 *
 * A fresh start creates its row first, so a failed create leaves existing rows
 * untouched, and then ends every other open row (WP-9.2): the row a source switch
 * leaves behind can never be resumed ([SessionStore.openSession] answers only the
 * newest open row), so closing it keeps orphans and their skip rows from growing
 * forever.
 */
class StartSessionUseCase(
    private val buildDeck: BuildDeckUseCase,
    private val decisions: DecisionStore,
    private val sessionStore: SessionStore,
) {
    suspend operator fun invoke(
        source: DeckSource,
        typeFilter: MediaTypeFilter,
        seedProvider: () -> Long,
    ): SessionStart {
        val resumable = sessionStore.openSession()?.takeIf { it.canResume(source, typeFilter) }
        return if (resumable != null) {
            val skipped = sessionStore.skippedIds(resumable.id)
            val built = buildDeck(source, typeFilter, resumable.seed)
            val counts = decisions.sessionCounts(resumable.id)
            // WP-6.4: `reviewed` counts committed DELETED rows too, so a
            // mid-session queue commit (FR-26) never under-counts the base.
            // FR-35: the memberships describe the sets, not the deck — they
            // survive the skipped filter unchanged.
            SessionStart(
                resumable.id,
                built.ids.filterNot { it in skipped },
                counts.reviewed + skipped.size,
                built.duplicateSets,
            )
        } else {
            val seed = seedProvider()
            val sessionId = sessionStore.create(source, typeFilter, seed)
            sessionStore.endOtherOpenSessions(sessionId)
            val built = buildDeck(source, typeFilter, seed)
            SessionStart(sessionId, built.ids, reviewedBase = 0, duplicateSets = built.duplicateSets)
        }
    }

    /** Same filter, same source; albums match on [DeckSource.Album.id] alone. */
    private fun SessionRecord.canResume(
        source: DeckSource,
        typeFilter: MediaTypeFilter,
    ): Boolean = this.typeFilter == typeFilter && sameSource(this.source, source)

    private fun sameSource(
        stored: DeckSource,
        wanted: DeckSource,
    ): Boolean =
        if (stored == wanted) {
            true
        } else {
            stored is DeckSource.Album && wanted is DeckSource.Album && stored.id == wanted.id
        }
}
