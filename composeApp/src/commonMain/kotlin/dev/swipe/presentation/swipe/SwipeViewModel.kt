package dev.swipe.presentation.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaLoader
import dev.swipe.data.SessionStore
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.Progress
import dev.swipe.domain.model.SessionRecord
import dev.swipe.domain.model.SessionSummary
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Swipe screen's deck pipeline (§6.5 steps 2–3 and 5; FR-12–16, FR-19–22, NFR-7).
 *
 * **Start** (§6.5 step 1): if [SessionStore.openSession] matches this screen's source
 * and filter, the session *resumes* — the stored seed rebuilds the same shuffle order
 * (it survives process death), the stored keeps and this session's skips stay excluded
 * so the screen reopens on the same card, and their count becomes the reviewed base;
 * otherwise a fresh session row is created. A failed open/build degrades to an empty
 * deck plus [ToastMessage.StartFailed] instead of crashing.
 *
 * **Decisions** (§6.5 step 5): [RecordDecisionUseCase] runs *before* anything moves.
 * Only after the write succeeds does the index advance, the resume position save
 * (FR-20), and the state re-emit. A failed write keeps the card exactly where it is,
 * sets [SwipeUiState.toast] to [ToastMessage.SaveFailed], and bumps
 * [SwipeUiState.revertToken] so the card animates back (NFR-7). A Delete on a favorite
 * records nothing and asks first (FR-22, [SwipeIntent.ConfirmFavoriteDelete]).
 *
 * **Undo** (FR-15) is the mirror image: the removal goes through
 * [UndoDecisionUseCase] *before* the index moves back — a failed removal leaves the
 * state untouched and shows the same error toast — and each successful decision
 * pushes an [UndoEntry] onto a 50-deep stack (oldest dropped).
 *
 * **Summary** (FR-21): running out of deck sets [SwipeUiState.finished] from this
 * session's stored counts and ends the session row.
 *
 * **Prefetch** (§6.5 step 3): after every move the window of current + next 3 is
 * requested from [MediaLoader.prefetch] and ids that left the window are cancelled.
 *
 * One-shot UI events go out through [events]; `Clock` stays out of this constructor —
 * nothing here timestamps anything (the stores stamp their own rows), so it joins only
 * when something actually reads it.
 *
 * This is a plain DI constructor wiring every collaborator of the §6.5 pipeline
 * through Koin; a parameter bag would only hide the graph.
 */
class SwipeViewModel(
    private val source: DeckSource,
    private val typeFilter: MediaTypeFilter,
    private val buildDeck: BuildDeckUseCase,
    private val recordDecision: RecordDecisionUseCase,
    private val undoDecision: UndoDecisionUseCase,
    private val mediaLibrary: MediaLibrary,
    private val mediaLoader: MediaLoader,
    private val decisions: DecisionStore,
    private val sessionStore: SessionStore,
    private val seedProvider: () -> Long,
) : ViewModel() {
    /** One pushed step of the undo stack (FR-15): what to remove and where to return. */
    private data class UndoEntry(
        val id: String,
        val verdict: Decision,
        val index: Int,
    )

    private val _uiState = MutableStateFlow(SwipeUiState())

    /** The one state flow (AGENTS.md ViewModel convention). */
    val uiState: StateFlow<SwipeUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<SwipeEvent>(Channel.UNLIMITED)

    /**
     * One-shot ViewModel → UI events (§6.6 events policy): a `Channel` consumed exactly
     * once, so a returning card command never replays on recomposition the way a state
     * field would.
     */
    val events: Flow<SwipeEvent> = eventChannel.receiveAsFlow()

    private var sessionId: String? = null

    private var deck: List<String> = emptyList()
    private var index = 0

    /**
     * Cards this session had already disposed when the deck was (re)built (FR-19):
     * after a resume they are out of the rebuilt deck but still count toward
     * `reviewed`, so `reviewed = reviewedBase + index` and
     * `total = deck.size + reviewedBase`.
     */
    private var reviewedBase = 0
    private var loadedCount = 0
    private var prefetched: List<String> = emptyList()
    private val metadata = mutableMapOf<String, MediaItem>()

    /** FR-15: successful decisions in order, newest last, capped at [UNDO_STACK_LIMIT]. */
    private val undoStack = ArrayDeque<UndoEntry>()

    init {
        // The queue badge tracks the shared delete queue live (FR-10, FR-23).
        viewModelScope.launch {
            decisions
                .deleteQueue()
                .map { queue -> queue.size }
                .collect { size -> _uiState.update { it.copy(queueCount = size) } }
        }
        viewModelScope.launch { start() }
    }

    /** The one entry point for user actions (AGENTS.md ViewModel convention). */
    fun onIntent(intent: SwipeIntent) {
        when (intent) {
            SwipeIntent.Keep -> decide(Decision.KEEP)
            SwipeIntent.Delete -> decide(Decision.DELETE)
            SwipeIntent.Skip -> decide(Decision.SKIP)
            SwipeIntent.Undo -> undo()
            SwipeIntent.ToggleMute ->
                // FR-16: remembered for this ViewModel's lifetime (the session).
                _uiState.update { it.copy(muted = !it.muted) }
            SwipeIntent.ConfirmFavoriteDelete -> {
                // FR-22: only meaningful while the confirm toast names a card; the
                // confirmed path skips the ask again (that is what was confirmed).
                if (_uiState.value.pendingFavoriteDelete != null) decide(Decision.DELETE, confirmed = true)
            }
            SwipeIntent.DismissToast ->
                _uiState.update { it.copy(toast = null, pendingFavoriteDelete = null) }
        }
    }

    /**
     * §6.5 step 1, resume or start fresh. A failure here (session open, deck build,
     * first metadata page) leaves the screen empty with [ToastMessage.StartFailed] —
     * there is nothing to revert, and the deck can be retried by reopening.
     */
    private suspend fun start() {
        try {
            val resumable =
                sessionStore
                    .openSession()
                    ?.takeIf { it.source == source && it.typeFilter == typeFilter }
            if (resumable != null) resume(resumable) else startFresh()
        } catch (cancellation: CancellationException) {
            throw cancellation // ViewModel teardown, not a failed start
        } catch (_: Exception) {
            _uiState.update { it.copy(toast = ToastMessage.StartFailed) }
        }
    }

    /** New session row with the stored seed (§6.5 step 1), deck, first page, prefetch, emit. */
    private suspend fun startFresh() {
        val seed = seedProvider()
        sessionId = sessionStore.create(source, typeFilter, seed)
        deck = buildDeck(source, typeFilter, seed)
        afterDeckReady()
    }

    /**
     * Resume (FR-20, §6.5 step 1): the stored seed rebuilds the same shuffle order
     * (it survives process death). [BuildDeckUseCase] already subtracts every stored
     * keep/delete row and this session's skips are filtered here, so the rebuilt deck
     * is exactly what is left — the position lands back on the same card by starting
     * at its head, and the stored rows become [reviewedBase] so the progress line
     * keeps telling the truth (FR-19).
     */
    private suspend fun resume(record: SessionRecord) {
        sessionId = record.id
        val skipped = sessionStore.skippedIds(record.id)
        deck = buildDeck(source, typeFilter, record.seed).filterNot { it in skipped }
        val counts = decisions.sessionCounts(record.id)
        reviewedBase = counts.kept + counts.queued + skipped.size
        index = 0
        afterDeckReady()
    }

    /** Shared tail of both start paths: first metadata page, prefetch, first emit. */
    private suspend fun afterDeckReady() {
        ensureMetadataLoaded()
        refreshPrefetch()
        publish()
    }

    /**
     * The decision path (FR-12–14, §6.5 step 5): (a) persist first; (b) on success
     * only, advance, save the resume position (FR-20), and emit; (c) on failure keep
     * the index and revert the card (NFR-7).
     *
     * A Delete on a favorite records nothing (FR-22): the card goes back through the
     * [SwipeEvent.ReturnCard] event, and the confirm toast names the id in
     * [SwipeUiState.pendingFavoriteDelete] until it is confirmed or dismissed.
     *
     * Concurrent taps (the buttons are always enabled): both intents capture the same
     * card before either write returns and the upsert keeps the decision durable, so
     * after the write each attempt re-checks that the deck head is still the card it
     * recorded — the loser discards its advance. A double tap can never skip an
     * undecided card (NFR-7).
     */
    private fun decide(
        decision: Decision,
        confirmed: Boolean = false,
    ) {
        val sessionId = sessionId ?: return // init has not finished; there is no card yet
        val id = deck.getOrNull(index) ?: return // exhausted: current stays null (§6.6)
        if (!confirmed && decision == Decision.DELETE && metadata[id]?.isFavorite == true) {
            // FR-22: ask before queueing a favorite; nothing is written yet.
            _uiState.update {
                it.copy(toast = ToastMessage.FavoriteConfirm, pendingFavoriteDelete = id)
            }
            eventChannel.trySend(SwipeEvent.ReturnCard(Decision.DELETE))
            return
        }
        viewModelScope.launch {
            try {
                recordDecision(id, decision, sessionId)
            } catch (cancellation: CancellationException) {
                throw cancellation // ViewModel teardown, not a failed save
            } catch (_: Exception) {
                // Any store failure reverts the card (NFR-7); it surfaces as the
                // SaveFailed toast, and there is no logging (NFR-6).
                _uiState.update {
                    it.copy(
                        toast = ToastMessage.SaveFailed,
                        revertToken = it.revertToken + 1,
                    )
                }
                return@launch
            }
            // Stale-advance guard: the deck head moved while this write was in flight,
            // so another intent already advanced past [id]; drop this advance (the
            // decision itself stays durable).
            if (deck.getOrNull(index) != id) return@launch
            // FR-15: the decision is durable, so it becomes an undo step now; the
            // oldest step falls off past the 50-cap.
            if (undoStack.size == UNDO_STACK_LIMIT) undoStack.removeFirst()
            undoStack.addLast(UndoEntry(id, decision, index))
            index += 1
            // The resume position is a best-effort hint (FR-20): the decision itself is
            // already durable, so a failed position write must not revert the card.
            savePosition()
            if (index == deck.size) finish() else publish()
            ensureMetadataLoaded()
            refreshPrefetch()
        }
    }

    /**
     * Undo (FR-15), the mirror of [decide]: [UndoDecisionUseCase] removes the stored
     * verdict *first*; only after that write succeeds does the index move back and
     * the card return with the "Undone" toast. A failed removal puts the step back on
     * the stack, leaves the state exactly as it was, and shows the error toast.
     *
     * The popped step carries the index it belongs to: after a concurrent race the
     * deck head may already be back there (double-tap Undo), and an entry whose index
     * is no longer behind the head is discarded rather than moving the deck forward.
     */
    private fun undo() {
        val sessionId = sessionId ?: return // init has not finished; nothing to undo
        val entry = undoStack.removeLastOrNull() ?: return // stack empty (§6.6 canUndo)
        viewModelScope.launch {
            try {
                undoDecision(entry.id, sessionId)
            } catch (cancellation: CancellationException) {
                throw cancellation // ViewModel teardown, not a failed removal
            } catch (_: Exception) {
                // The removal failed, so the decision is still stored: the step goes
                // back on the stack and the deck does not move (FR-15, NFR-7 mirror).
                if (undoStack.lastOrNull() != entry) undoStack.addLast(entry)
                _uiState.update { it.copy(toast = ToastMessage.SaveFailed) }
                return@launch
            }
            if (entry.index < index) {
                index = entry.index
            } else {
                // Safety net (see the doc): the deck did not move, but a stored row
                // is gone, so keep `reviewedBase + index` in step with the rows.
                reviewedBase -= 1
            }
            savePosition()
            publish(toast = ToastMessage.Undone)
            eventChannel.trySend(SwipeEvent.ReturnCard(entry.verdict))
            ensureMetadataLoaded()
            refreshPrefetch()
        }
    }

    /** Best-effort resume position save; the decision itself is already durable (FR-20). */
    private suspend fun savePosition() {
        val sessionId = sessionId ?: return
        try {
            sessionStore.updatePosition(sessionId, reviewedBase + index)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Ignored on purpose; see above.
        }
    }

    /**
     * FR-21: the deck ran out. The counts come from the stored rows so they cover the
     * whole session across process death, the session row is ended, and the undo
     * stack empties with it (the review run is over). A failed count query leaves the
     * screen on the exhausted deck with the error toast rather than wrong numbers.
     */
    private suspend fun finish() {
        val sessionId = sessionId ?: return
        val summary =
            try {
                val counts = decisions.sessionCounts(sessionId)
                SessionSummary(
                    kept = counts.kept,
                    queued = counts.queued,
                    skipped = sessionStore.skippedIds(sessionId).size,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.update { it.copy(toast = ToastMessage.SaveFailed) }
                return
            }
        undoStack.clear()
        _uiState.update {
            it.copy(
                current = null,
                next = null,
                progress = Progress(reviewed = reviewedBase + index, total = deck.size + reviewedBase),
                finished = summary,
                canUndo = false,
                toast = null,
                pendingFavoriteDelete = null,
            )
        }
        // Best effort: an un-stamped end leaves the row open, and a resumed exhausted
        // deck simply rebuilds empty — no decision is at stake.
        try {
            sessionStore.end(sessionId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Ignored on purpose; see above.
        }
    }

    /**
     * §6.5 step 2: metadata in pages of 20, loading the next page while the index is
     * within 5 of the loaded end. Called after each page decision so the first card
     * emits as soon as its page is in.
     */
    private suspend fun ensureMetadataLoaded() {
        while (loadedCount < deck.size && index + METADATA_PAGE_TRIGGER >= loadedCount) {
            val page = deck.subList(loadedCount, minOf(deck.size, loadedCount + METADATA_PAGE_SIZE))
            for (item in mediaLibrary.items(page)) {
                metadata[item.id] = item
            }
            loadedCount += page.size
        }
    }

    /**
     * §6.5 step 3: keep current + next 3 requested from [MediaLoader.prefetch] and
     * cancel ids that left the window. Both calls pass the identical target size —
     * PhotoKit only cancels prefetches made at the same size (§6.4).
     */
    private fun refreshPrefetch() {
        val window =
            (index until index + PREFETCH_WINDOW)
                .mapNotNull { deck.getOrNull(it) }
        val stale = prefetched.filterNot { it in window }
        if (stale.isNotEmpty()) {
            mediaLoader.cancelPrefetch(stale, PREFETCH_TARGET_PX)
        }
        if (window.isNotEmpty()) {
            mediaLoader.prefetch(window, PREFETCH_TARGET_PX)
        }
        prefetched = window
    }

    /**
     * Emit the deck slice. A move always clears the favorite confirm (its card is no
     * longer up); [toast], when given, replaces the message (the "Undone" notice).
     */
    private fun publish(toast: ToastMessage? = null) {
        _uiState.update {
            it.copy(
                current = cardAt(index),
                next = cardAt(index + 1),
                progress = Progress(reviewed = reviewedBase + index, total = deck.size + reviewedBase),
                canUndo = undoStack.isNotEmpty(),
                toast = toast,
                pendingFavoriteDelete = null,
            )
        }
    }

    /** The card at [i], or null past the deck's end or before its metadata arrives. */
    private fun cardAt(i: Int): CardState? = deck.getOrNull(i)?.let { metadata[it] }?.let(CardState::from)

    /** §6.5 step 3: leaving the deck cancels the still-warm prefetch window. */
    public override fun onCleared() {
        if (prefetched.isNotEmpty()) {
            mediaLoader.cancelPrefetch(prefetched, PREFETCH_TARGET_PX)
            prefetched = emptyList()
        }
    }

    private companion object {
        /** §6.5 step 2: metadata pages of 20 ids. */
        const val METADATA_PAGE_SIZE = 20

        /** §6.5 step 2: load the next page within 5 of the loaded end. */
        const val METADATA_PAGE_TRIGGER = 5

        /** §6.5 step 3: prefetch current + next 3. */
        const val PREFETCH_WINDOW = 4

        /**
         * Prefetch asks for the screen's pixel size (§6.5 step 3); the loader caps
         * every request at the screen's pixel size (NFR-2, R1), so an unbounded
         * request resolves to exactly that. [MediaLoader.cancelPrefetch] must pass
         * the identical value, and WP-1.9's cards request the same
         * [MediaLoader.FULL_TARGET_PX] so a prefetched decode lands in the card's
         * cache key — one shared constant on the interface.
         */
        val PREFETCH_TARGET_PX = MediaLoader.FULL_TARGET_PX

        /** FR-15: the undo stack holds up to 50 steps per session. */
        const val UNDO_STACK_LIMIT = 50
    }
}
