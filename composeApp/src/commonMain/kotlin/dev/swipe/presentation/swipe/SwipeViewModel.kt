package dev.swipe.presentation.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaLoader
import dev.swipe.data.MediaSharer
import dev.swipe.data.SessionStore
import dev.swipe.data.VideoPreloader
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.MediaType
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.Progress
import dev.swipe.domain.model.SessionSummary
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.UndoDecisionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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
 * **Share** (FR-32): [SwipeIntent.Share] hands the current card to [MediaSharer]
 * with [SwipeUiState.sharing] up while the media loads, and shows
 * [ToastMessage.ShareFailed] if it cannot. It never records a decision, moves the
 * index, or touches the undo stack; a deck move during the load abandons it.
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
    private val videoPreloader: VideoPreloader,
    private val mediaSharer: MediaSharer,
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

    /** §6.5 step 4: the window's video ids kept warm in [VideoPreloader] (FR-16). */
    private var prefetchedVideos: List<String> = emptyList()
    private val metadata = mutableMapOf<String, MediaItem>()

    /** FR-15: successful decisions in order, newest last, capped at [UNDO_STACK_LIMIT]. */
    private val undoStack = ArrayDeque<UndoEntry>()

    /** FR-15: the popped step whose removal is in flight; further Undo taps are ignored. */
    private var undoInFlight: UndoEntry? = null

    /**
     * FR-32: the single-flight share. Nothing here writes a decision or moves the
     * deck — the share sheet's outcome is not even reported back. A failed load
     * shows [ToastMessage.ShareFailed]; every deck move cancels a share still
     * loading ([publish], [finish]), so a late sheet never opens for a card that
     * is no longer up.
     */
    private val shareLauncher =
        ShareLauncher(
            scope = viewModelScope,
            sharer = mediaSharer,
            onLoading = { loading -> _uiState.update { it.copy(sharing = loading) } },
            onFailed = {
                _uiState.update { it.copy(toast = ToastMessage.ShareFailed, pendingFavoriteDelete = null) }
            },
        )

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
            SwipeIntent.Keep -> {
                decide(Decision.KEEP)
            }

            SwipeIntent.Delete -> {
                decide(Decision.DELETE)
            }

            SwipeIntent.Skip -> {
                decide(Decision.SKIP)
            }

            SwipeIntent.Undo -> {
                undo()
            }

            SwipeIntent.ToggleMute -> {
                // FR-16: remembered for this ViewModel's lifetime (the session).
                _uiState.update { it.copy(muted = !it.muted) }
            }

            SwipeIntent.ConfirmFavoriteDelete -> {
                // FR-22: only meaningful while the confirm names a card that is still
                // up. The pending id is cleared *synchronously* here, so a second tap
                // during the write's suspension window finds nothing pending — the
                // confirmed write can never target the next card.
                val pending = _uiState.value.pendingFavoriteDelete
                if (pending != null && deck.getOrNull(index) == pending) {
                    _uiState.update { it.copy(toast = null, pendingFavoriteDelete = null) }
                    decide(Decision.DELETE, confirmed = true)
                }
            }

            SwipeIntent.DismissToast -> {
                _uiState.update { it.copy(toast = null, pendingFavoriteDelete = null) }
            }

            SwipeIntent.Share -> {
                // FR-32: the current card only; no card, nothing to share.
                deck.getOrNull(index)?.let(shareLauncher::launch)
            }
        }
    }

    /**
     * §6.5 step 1, resume or start fresh, then first page, prefetch, first emit. A
     * failure anywhere in the start path (session open, deck build, first metadata
     * page) leaves the screen empty with [ToastMessage.StartFailed] — there is
     * nothing to revert, and the deck can be retried by reopening.
     *
     * Resume (FR-20): [SessionStore.openSession] matching this screen's source and
     * filter resumes that session — the stored seed rebuilds the same shuffle order
     * (it survives process death), the stored keeps and this session's skips stay
     * excluded so the screen reopens on the same card, and their count becomes the
     * [reviewedBase]. Anything else (no open session, mismatched source or filter)
     * creates a fresh session row with the stored seed.
     */
    private suspend fun start() {
        try {
            val resumable =
                sessionStore
                    .openSession()
                    ?.takeIf { it.source == source && it.typeFilter == typeFilter }
            if (resumable != null) {
                sessionId = resumable.id
                val skipped = sessionStore.skippedIds(resumable.id)
                deck = buildDeck(source, typeFilter, resumable.seed).filterNot { it in skipped }
                val counts = decisions.sessionCounts(resumable.id)
                // WP-6.4: `reviewed` counts committed DELETED rows too, so a
                // mid-session queue commit (FR-26) never under-counts the base.
                reviewedBase = counts.reviewed + skipped.size
                index = 0
            } else {
                val seed = seedProvider()
                sessionId = sessionStore.create(source, typeFilter, seed)
                deck = buildDeck(source, typeFilter, seed)
            }
            ensureMetadataLoaded()
            refreshPrefetch()
            // A resumed session that is already exhausted (every card disposed before
            // process death, the summary never delivered) retries FR-21 right away.
            if (deck.isEmpty() && reviewedBase > 0) finish() else publish()
        } catch (cancellation: CancellationException) {
            throw cancellation // ViewModel teardown, not a failed start
        } catch (_: Exception) {
            _uiState.update { it.copy(toast = ToastMessage.StartFailed) }
        }
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
        } else {
            viewModelScope.launch {
                val saved =
                    try {
                        recordDecision(id, decision, sessionId)
                        true
                    } catch (cancellation: CancellationException) {
                        throw cancellation // ViewModel teardown, not a failed save
                    } catch (_: Exception) {
                        // Any store failure reverts the card (NFR-7); it surfaces as the
                        // SaveFailed toast, and there is no logging (NFR-6).
                        false
                    }
                if (!saved) {
                    _uiState.update {
                        it.copy(
                            toast = ToastMessage.SaveFailed,
                            revertToken = it.revertToken + 1,
                        )
                    }
                } else if (deck.getOrNull(index) == id) {
                    // Stale-advance guard: only advance while the deck head is still the
                    // card this write recorded; a race loser discards its advance (the
                    // decision itself stays durable).
                    // FR-15: the decision is durable, so it becomes an undo step now; the
                    // oldest step falls off past the 50-cap.
                    if (undoStack.size == UNDO_STACK_LIMIT) undoStack.removeFirst()
                    undoStack.addLast(UndoEntry(id, decision, index))
                    index += 1
                    // The resume position is a best-effort hint (FR-20): the decision
                    // itself is already durable, so a failed position write must not
                    // revert the card.
                    savePosition()
                    if (index == deck.size) finish() else publish()
                    ensureMetadataLoaded()
                    refreshPrefetch()
                }
            }
        }
    }

    /**
     * Undo (FR-15), the mirror of [decide]: [UndoDecisionUseCase] removes the stored
     * verdict *first*; only after that write succeeds does the index move back and
     * the card return with the "Undone" toast. A failed removal puts the step back on
     * the stack, leaves the state exactly as it was, and shows the error toast.
     *
     * The removal is single-flight via [undoInFlight]: while one is in progress,
     * further Undo taps are ignored. That keeps the popped entry on top of every
     * later push and the index monotonic between pop and completion, so a failed
     * removal can always restore stack order with a plain re-add, and the entry's
     * index is guaranteed to sit behind the deck head when the removal lands.
     */
    private fun undo() {
        val sessionId = sessionId ?: return // init has not finished; nothing to undo
        // FR-15: single-flight, and a no-op on an empty stack (§6.6 canUndo).
        val entry = if (undoInFlight == null) undoStack.removeLastOrNull() else null
        entry ?: return
        undoInFlight = entry
        viewModelScope.launch {
            val removed =
                try {
                    undoDecision(entry.id, sessionId)
                    true
                } catch (cancellation: CancellationException) {
                    throw cancellation // ViewModel teardown, not a failed removal
                } catch (_: Exception) {
                    false
                }
            undoInFlight = null
            if (!removed) {
                // The removal failed, so the decision is still stored: the step goes
                // back on the stack — nothing else could have been popped while it
                // was in flight (single-flight), so re-adding restores the exact
                // order — and the deck does not move (FR-15, NFR-7 mirror).
                undoStack.addLast(entry)
                _uiState.update { it.copy(toast = ToastMessage.SaveFailed) }
                return@launch
            }
            // Single-flight guarantees `entry.index < index` here: the index only
            // moves back through undo, and no other undo is in flight.
            index = entry.index
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
     * stack empties with it (the review run is over). A failed count query still
     * publishes the exhausted state — the card is already disposed, so leaving the
     * old card on screen would lie — with [ToastMessage.SaveFailed] instead of a
     * summary, and the session row stays open so resuming an exhausted session
     * retries the summary ([start]).
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
                null
            }
        undoStack.clear()
        shareLauncher.cancel()
        _uiState.update {
            it.copy(
                current = null,
                next = null,
                progress = Progress(reviewed = reviewedBase + index, total = deck.size + reviewedBase),
                finished = summary,
                canUndo = false,
                toast = if (summary == null) ToastMessage.SaveFailed else null,
                pendingFavoriteDelete = null,
                sharing = false,
            )
        }
        if (summary == null) {
            // Counts unavailable: keep the row open so the next resume retries.
            return
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
     *
     * §6.5 step 4: the window's videos are pre-playlisted through [VideoPreloader]
     * (FR-16); ids that left the window are released again. `preload` is idempotent
     * for warm ids, so re-entering a window never re-requests (the cache dedupes).
     * Live Photos are deliberately absent — their paired video is exported on mount
     * (FR-17), not ahead of the card.
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
        val videos = window.filter { metadata[it]?.type == MediaType.VIDEO }
        prefetchedVideos.filterNot { it in videos }.forEach(videoPreloader::cancel)
        videos.filterNot { it in prefetchedVideos }.forEach(videoPreloader::preload)
        prefetchedVideos = videos
    }

    /**
     * Emit the deck slice. A move always clears the favorite confirm (its card is no
     * longer up) and abandons a loading share (FR-32); [toast], when given, replaces
     * the message (the "Undone" notice).
     */
    private fun publish(toast: ToastMessage? = null) {
        shareLauncher.cancel()
        _uiState.update {
            it.copy(
                current = cardAt(index),
                next = cardAt(index + 1),
                progress = Progress(reviewed = reviewedBase + index, total = deck.size + reviewedBase),
                canUndo = undoStack.isNotEmpty(),
                toast = toast,
                pendingFavoriteDelete = null,
                sharing = false,
            )
        }
    }

    /** The card at [i], or null past the deck's end or before its metadata arrives. */
    private fun cardAt(i: Int): CardState? = deck.getOrNull(i)?.let { metadata[it] }?.let(CardState::from)

    /** §6.5 steps 3–4: leaving the deck cancels the still-warm image and video windows. */
    public override fun onCleared() {
        if (prefetched.isNotEmpty()) {
            mediaLoader.cancelPrefetch(prefetched, PREFETCH_TARGET_PX)
            prefetched = emptyList()
        }
        if (prefetchedVideos.isNotEmpty()) {
            prefetchedVideos.forEach(videoPreloader::cancel)
            prefetchedVideos = emptyList()
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
