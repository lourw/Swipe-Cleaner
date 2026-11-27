package dev.swipe.presentation.swipe

import dev.swipe.data.SensitivityAnalyzer
import dev.swipe.domain.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * FR-33: screens the prefetch window through [SensitivityAnalyzer] and drops
 * flagged ids from the [DeckCursor] so they never become a card. Analysis only
 * ever touches ids inside `[index, index + PrefetchWindow.WINDOW)` — never a
 * whole-library scan (hard rule 6): [settle] pages metadata and awaits the
 * verdicts for the visible pair (current + next) before a deck move is
 * published; [screenAhead] asks about positions +2 and +3 in the background.
 * Videos are never analyzed (FR-33).
 *
 * Fails open: the analyzer answers false for any failure, so an item is only
 * hidden on a real flag. Verdicts are cached for one deck run (the filter is
 * built per deck). Nothing here writes a decision (hard rule 2): dropped ids
 * are removed from the cursor before any card or DecisionStore write.
 */
class SensitiveFilter(
    private val analyzer: SensitivityAnalyzer,
    /** `SettingsStore.hideSensitive`, read on every call so a toggle takes effect immediately. */
    private val enabled: () -> Boolean,
) {
    /** This deck run only; true = flagged sensitive. */
    private val verdicts = mutableMapOf<String, Boolean>()

    private val active: Boolean get() = enabled() && analyzer.isAvailable()

    /**
     * Replaces `cursor.ensureMetadataLoaded()` before a publish: pages metadata,
     * then screens and drops flagged ids at the head (current + next) until
     * both are clean.
     */
    suspend fun settle(cursor: DeckCursor) {
        cursor.ensureMetadataLoaded()
        if (!active) return
        var flagged = screen(cursor, cursor.index, cursor.index + HEAD)
        while (flagged.isNotEmpty()) {
            cursor.drop(flagged)
            cursor.ensureMetadataLoaded()
            flagged = screen(cursor, cursor.index, cursor.index + HEAD)
        }
    }

    /**
     * Screens the rest of the window (+2, +3) in the background. Drops only ids
     * still past the visible pair when the analysis returns; anything that
     * reached current/next is [settle]'s to handle.
     */
    fun screenAhead(
        scope: CoroutineScope,
        cursor: DeckCursor,
    ) {
        if (!active) return
        scope.launch {
            val flagged = screen(cursor, cursor.index + HEAD, cursor.index + PrefetchWindow.WINDOW)
            val droppable = flagged.filterTo(mutableSetOf()) { cursor.deck.indexOf(it) >= cursor.index + HEAD }
            if (droppable.isNotEmpty()) cursor.drop(droppable)
        }
    }

    /** Verdicts for the window slice `[from, until)`; videos are never asked (FR-33). */
    private suspend fun screen(
        cursor: DeckCursor,
        from: Int,
        until: Int,
    ): Set<String> {
        val ids =
            (from until until)
                .mapNotNull { cursor.deck.getOrNull(it) }
                .filter { cursor.metadata[it]?.type != MediaType.VIDEO }
        for (id in ids) {
            if (id !in verdicts) verdicts[id] = analyzer.isSensitive(id)
        }
        return ids.filterTo(mutableSetOf()) { verdicts[it] == true }
    }

    companion object {
        /** Current + next: the two cards on screen, awaited before a publish. */
        const val HEAD = 2
    }
}
