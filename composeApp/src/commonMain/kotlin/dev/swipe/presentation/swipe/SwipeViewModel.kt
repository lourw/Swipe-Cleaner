package dev.swipe.presentation.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaLoader
import dev.swipe.data.MediaSharer
import dev.swipe.data.VideoPreloader
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.Progress
import dev.swipe.domain.usecase.FinishSessionUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import dev.swipe.domain.usecase.StartSessionUseCase
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
 * **Start** (§6.5 step 1): [StartSessionUseCase] resumes or starts fresh — a stored
 * open session matching this screen's source and filter reopens on the same card (the
 * stored seed rebuilds the same shuffle order; it survives process death, and the
 * stored keeps and this session's skips stay excluded), otherwise a fresh session row
 * is created. A failed open/build degrades to an empty
 * deck plus [ToastMessage.StartFailed] instead of crashing.
 *
 * **Decisions** (§6.5 step 5): [RecordDecisionUseCase] runs *before* anything moves.
 * Only after the write succeeds does the index advance and the state re-emit. A failed
 * write keeps the card exactly where it is,
 * sets [SwipeUiState.toast] to [ToastMessage.SaveFailed], and bumps
 * [SwipeUiState.revertToken] so the card animates back (NFR-7). A Delete on a favorite
 * records nothing and asks first (FR-22, [SwipeIntent.ConfirmFavoriteDelete]).
 *
 * **Undo** (FR-15) is the mirror image: the removal goes through
 * [UndoDecisionUseCase] *before* the index moves back — a failed removal leaves the
 * state untouched and shows the same error toast — and each successful decision
 * pushes an [UndoEntry] onto a 50-deep stack (oldest dropped). Undo is ignored
 * while a decision write is in flight ([decideInFlight]), the mirror of the
 * single-flight removal: the deck never rewinds under a completing write.
 *
 * **Summary** (FR-21): running out of deck sets [SwipeUiState.finished] from this
 * session's stored counts and ends the session row. A deck that opens empty shows no
 * summary — the §5.8 empty state stands in — and its row ends immediately ([start]).
 * Until the start path settles, [SwipeUiState.loading] keeps the placeholder card up
 * (FR-6, WP-8.3), and deck ids whose metadata never resolves are dropped from the
 * deck rather than ending it or showing a blank card.
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
    private val startSession: StartSessionUseCase,
    private val finishSession: FinishSessionUseCase,
    private val recordDecision: RecordDecisionUseCase,
    private val undoDecision: UndoDecisionUseCase,
    private val mediaLibrary: MediaLibrary,
    private val mediaLoader: MediaLoader,
    private val videoPreloader: VideoPreloader,
    private val mediaSharer: MediaSharer,
    private val decisions: DecisionStore,
    private val seedProvider: () -> Long,
) : ViewModel() {
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

    /**
     * Cards this session had already disposed when the deck was (re)built (FR-19):
     * after a resume they are out of the rebuilt deck but still count toward
     * `reviewed`, so `reviewed = reviewedBase + cursor.index` and
     * `total = cursor.deck.size + reviewedBase`.
     */
    private var reviewedBase = 0

    /** §6.5 step 2: the deck, its head, and the metadata paged around the head. */
    private val cursor = DeckCursor(mediaLibrary)

    /** §6.5 steps 3–4: the image and video windows kept warm around the deck head. */
    private val prefetch = PrefetchWindow(mediaLoader, videoPreloader)

    /** FR-15: successful decisions in order, newest last, capped at [UndoStack.LIMIT]. */
    private val undoStack = UndoStack()

    /**
     * FR-15: cards whose decision write is in flight. While non-empty, [undo]
     * is ignored — the mirror of the undo stack's single-flight removal holding
     * further Undo taps. Rewinding
     * under a completing write would re-show the card being decided, and that write's
     * stale-advance guard would discard the advance, leaving a stored verdict behind a
     * card the user then decides a second time; ignoring the tap keeps the deck and
     * the stored rows in step (NFR-7).
     *
     * FR-13: a decision naming a card already in this set is dropped too — the UI
     * sends each decision exactly once (its button path refuses a second fly-off),
     * so a repeat is a duplicate delivery and one write per verdict is all it needs.
     */
    private val decideInFlight = mutableSetOf<String>()

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
            is SwipeIntent.Decide -> {
                decide(intent.id, intent.verdict)
            }

            SwipeIntent.Undo -> {
                undo()
            }

            SwipeIntent.ToggleMute -> {
                // FR-16: remembered for this ViewModel's lifetime (the session).
                _uiState.update { it.copy(muted = !it.muted) }
            }

            is SwipeIntent.ConfirmFavoriteDelete -> {
                // FR-22: only meaningful while the confirm names the card still up
                // and the intent names that same card. The pending id is cleared
                // *synchronously* here, so a second tap during the write's suspension
                // window finds nothing pending — the confirmed write can never
                // target the next card.
                val pending = _uiState.value.pendingFavoriteDelete
                if (pending != null && intent.id == pending && cursor.currentId == pending) {
                    _uiState.update { it.copy(toast = null, pendingFavoriteDelete = null) }
                    decide(pending, Decision.DELETE, confirmed = true)
                }
            }

            SwipeIntent.DismissToast -> {
                _uiState.update { it.copy(toast = null, pendingFavoriteDelete = null) }
            }

            SwipeIntent.Share -> {
                // FR-32: the current card only; no card, nothing to share.
                cursor.currentId?.let(shareLauncher::launch)
            }
        }
    }

    /**
     * §6.5 step 1: [StartSessionUseCase] resumes or starts fresh, then first page,
     * prefetch, first emit. A failure anywhere in the start path (session open,
     * deck build, first metadata page) leaves the screen empty with
     * [ToastMessage.StartFailed] — there is nothing to revert, and the deck can be
     * retried by reopening.
     *
     * A deck that opens empty (fresh or resumed with nothing reviewed) publishes the
     * §5.8 empty state and its row ends right away, so `openSession()` never holds a
     * session with nothing to resume (FR-21, WP-7.3). A resumed session that is
     * already exhausted retries the FR-21 summary right away.
     */
    private suspend fun start() {
        try {
            val started = startSession(source, typeFilter, seedProvider)
            sessionId = started.sessionId
            reviewedBase = started.reviewedBase
            cursor.reset(started.deck)
            cursor.ensureMetadataLoaded()
            prefetch.refresh(cursor.deck, cursor.index, cursor.metadata)
            when {
                // Every card disposed before process death, the summary never delivered.
                cursor.isEmpty && reviewedBase > 0 -> {
                    finish()
                }

                cursor.isEmpty -> {
                    publish()
                    val emptySessionId = sessionId
                    if (emptySessionId != null) {
                        finishSession.endEmptyDeck(emptySessionId)
                    }
                }

                else -> {
                    publish()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation // ViewModel teardown, not a failed start
        } catch (_: Exception) {
            _uiState.update { it.copy(toast = ToastMessage.StartFailed, loading = false) }
        }
    }

    /**
     * The decision path (FR-12–14, §6.5 step 5): (a) persist first; (b) on success
     * only, advance and emit; (c) on failure keep
     * the index and revert the card (NFR-7).
     *
     * Every decision names the card it was made for: an intent whose [id] is not the
     * card on screen is ignored (FR-12, FR-13) — a tap that lands while the previous
     * card is still flying off never records against the leaving card or the one
     * behind it. A second verdict for a card whose write is already in flight is
     * dropped for the same reason: the in-flight write is that card's commit, so a
     * double tap can never skip an undecided card (NFR-7).
     *
     * A Delete on a favorite records nothing (FR-22): the card goes back through the
     * [SwipeEvent.ReturnCard] event, and the favorite confirm card names the id in
     * [SwipeUiState.pendingFavoriteDelete] until it is confirmed, dismissed, or a
     * drag starts on the card (U3, WP-8.9).
     */
    private fun decide(
        id: String,
        decision: Decision,
        confirmed: Boolean = false,
    ) {
        val sessionId = sessionId ?: return // init has not finished; there is no card yet
        // FR-12/FR-13: the decision belongs to the card it named; a stale id (the
        // card already flew off) is dropped, nothing is written, nothing moves. And
        // one write per card: while the card's own verdict is in flight it is that
        // card's commit and the card has already advanced, so a second verdict
        // naming it is dropped and nothing is lost.
        if (cursor.currentId != id || id in decideInFlight) return
        if (!confirmed && decision == Decision.DELETE && cursor.metadata[id]?.isFavorite == true) {
            // FR-22: ask before queueing a favorite; nothing is written yet.
            _uiState.update {
                it.copy(toast = ToastMessage.FavoriteConfirm, pendingFavoriteDelete = id)
            }
            eventChannel.trySend(SwipeEvent.ReturnCard(Decision.DELETE))
        } else {
            // FR-15: hold Undo for the duration of the write (WP-7.2).
            decideInFlight += id
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
                    } finally {
                        // Cleared the moment the write returns. Everything below runs to
                        // the next suspension point on the ViewModel's single dispatcher,
                        // so an Undo tap can never slip in between the write and the
                        // index advance.
                        decideInFlight -= id
                    }
                if (!saved) {
                    _uiState.update {
                        it.copy(
                            toast = ToastMessage.SaveFailed,
                            revertToken = it.revertToken + 1,
                        )
                    }
                } else if (cursor.currentId == id) {
                    // Stale-advance guard: only advance while the deck head is still the
                    // card this write recorded; a race loser discards its advance (the
                    // decision itself stays durable).
                    // FR-15: the decision is durable, so it becomes an undo step now; the
                    // oldest step falls off past the 50-cap.
                    undoStack.push(UndoEntry(id, decision, cursor.index))
                    cursor.advance()
                    // FR-6 (WP-8.3): page metadata and drop unresolvable ids *before*
                    // testing exhaustion, so a deck whose tail ids never resolve
                    // carries on to reviewable cards instead of finishing early.
                    pageMetadataQuietly { cursor.ensureMetadataLoaded() }
                    if (cursor.isExhausted) finish() else publish()
                    prefetch.refresh(cursor.deck, cursor.index, cursor.metadata)
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
     * The removal is single-flight via [UndoStack.popForRemoval]: while one is in
     * progress, further Undo taps are ignored. That keeps the popped entry on top of
     * every later push and the index monotonic between pop and completion, so a
     * failed removal can always restore stack order with a plain re-add, and the
     * entry's index is guaranteed to sit behind the deck head when the removal lands.
     *
     * A tap is likewise ignored while a [decide] write is in flight
     * ([decideInFlight], WP-7.2): rewinding under a completing write re-shows
     * the card being decided, so the tap drops rather than rewinds or queues.
     */
    private fun undo() {
        val sessionId = sessionId ?: return // init has not finished; nothing to undo
        // FR-15: single-flight, no-op on an empty stack (§6.6 canUndo), and held
        // while a decision write is in flight (WP-7.2).
        val entry = if (decideInFlight.isEmpty()) undoStack.popForRemoval() else null
        entry ?: return
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
            if (!removed) {
                // The removal failed, so the decision is still stored: the step goes
                // back on the stack — nothing else could have been popped while it
                // was in flight (single-flight), so re-adding restores the exact
                // order — and the deck does not move (FR-15, NFR-7 mirror).
                undoStack.restore(entry)
                _uiState.update { it.copy(toast = ToastMessage.SaveFailed) }
                return@launch
            }
            undoStack.removalSettled()
            // Single-flight guarantees `entry.index < cursor.index` here: the head
            // only moves back through undo, and no other undo is in flight.
            cursor.rewindTo(entry.index)
            publish(toast = ToastMessage.Undone)
            eventChannel.trySend(SwipeEvent.ReturnCard(entry.verdict))
            cursor.ensureMetadataLoaded()
            prefetch.refresh(cursor.deck, cursor.index, cursor.metadata)
        }
    }

    /**
     * FR-21: the deck ran out. [FinishSessionUseCase] builds the summary from the
     * stored rows (they cover the whole session across process death) and ends the
     * session row; a failed count query yields no summary and the row stays open so
     * resuming an exhausted session retries. A failed summary publishes the
     * exhausted state with [ToastMessage.SaveFailed] instead — the card is already
     * disposed, so leaving the old card on screen would lie. The undo stack empties
     * with the run (there is nothing to undo past the summary).
     */
    private suspend fun finish() {
        val sessionId = sessionId ?: return
        val summary = finishSession(sessionId)
        undoStack.clear()
        shareLauncher.cancel()
        _uiState.update {
            it.copy(
                current = null,
                next = null,
                progress =
                    Progress(
                        reviewed = reviewedBase + cursor.index,
                        total = cursor.deck.size + reviewedBase,
                    ),
                finished = summary,
                canUndo = false,
                toast = if (summary == null) ToastMessage.SaveFailed else null,
                pendingFavoriteDelete = null,
                sharing = false,
                loading = false,
            )
        }
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
                current = cardAt(cursor.index),
                next = cardAt(cursor.index + 1),
                progress =
                    Progress(
                        reviewed = reviewedBase + cursor.index,
                        total = cursor.deck.size + reviewedBase,
                    ),
                canUndo = undoStack.isNotEmpty(),
                toast = toast,
                pendingFavoriteDelete = null,
                sharing = false,
                loading = false,
            )
        }
    }

    /** The card at [i], or null past the deck's end or before its metadata arrives. */
    private fun cardAt(i: Int): CardState? = cursor.itemAt(i)?.let(CardState::from)

    /** §6.5 steps 3–4: leaving the deck cancels the still-warm image and video windows. */
    public override fun onCleared() {
        prefetch.release()
    }
}

/**
 * Runs a deck metadata [pass] with a failed page fetch swallowed (FR-6, WP-8.3):
 * the ids stay in the deck for the next trigger, and the durable decision that
 * triggered the pass still advances (NFR-7). Cancellation always propagates.
 */
private suspend fun pageMetadataQuietly(pass: suspend () -> Unit) {
    try {
        pass()
    } catch (cancellation: CancellationException) {
        throw cancellation // ViewModel teardown, not a failed page
    } catch (_: Exception) {
        // Ignored on purpose; see above.
    }
}
