package dev.swipe.presentation.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.AudioSession
import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaHider
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaLoader
import dev.swipe.data.MediaSharer
import dev.swipe.data.SensitivityAnalyzer
import dev.swipe.data.SettingsStore
import dev.swipe.data.VideoPreloader
import dev.swipe.domain.model.Decision
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.DuplicateSetMembership
import dev.swipe.domain.model.HideResult
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
 * pushes an [UndoEntry] onto a 50-deep stack (oldest dropped). An Undo that lands
 * while a decision write is in flight — the write half of the card's fly-off
 * window — is *queued* ([pendingUndo]) and fires once the write settles: the deck
 * still never rewinds under a completing write, but the tap is not lost (WP-9.10).
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
 * **Hide** (FR-38): [SwipeIntent.Hide] flips the card's `hidden` flag through
 * [MediaHider] — immediately, with no confirm and no decision row — while
 * [SwipeUiState.hiding] is up; it is also held while a decision write is in flight
 * ([decideInFlight]). Success pushes a verdict-less [UndoEntry], advances the deck
 * like a decision, and toasts [ToastMessage.Hidden]; failure keeps the card and toasts
 * [ToastMessage.HideFailed] (NFR-7). Undoing the entry unhides exactly the id stored
 * in it and returns the card in place ([SwipeEvent.ReturnCardInPlace]).
 *
 * **Prefetch** (§6.5 step 3): after every move the window of current + next 3 is
 * requested from [MediaLoader.prefetch] and ids that left the window are cancelled.
 *
 * **Sensitive (FR-33)**: [SensitiveFilter] screens the prefetch window: ids the
 * analyzer flags leave the deck before they can become the current or next card,
 * and no decision row is ever written for them.
 *
 * **Video sound** (FR-16): the session starts muted unless [SettingsStore.playVideoSound]
 * is on — read once at construction, so the setting applies to the *next* session and
 * this one remembers only [SwipeIntent.ToggleMute]. **Autoplay** (FR-29) rides the
 * state instead: [SwipeUiState.autoplayVideos] mirrors `SettingsStore.autoplayVideos`
 * live, so the screen never injects the store (hard rule 5).
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
    private val mediaHider: MediaHider,
    private val audioSession: AudioSession,
    settings: SettingsStore,
    private val decisions: DecisionStore,
    private val seedProvider: () -> Long,
    private val sensitiveFilter: SensitiveFilter = SensitiveFilter(SensitivityAnalyzer.Unavailable) { false },
) : ViewModel() {
    private val _uiState = MutableStateFlow(SwipeUiState(muted = !settings.playVideoSound.value))

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

    /**
     * FR-35: place-in-set info for the Likely Duplicates deck, from the same
     * build as the deck (§6.5); empty for every other source. Set once per
     * [start], read by [cardAt].
     */
    private var duplicateSets: Map<String, DuplicateSetMembership> = emptyMap()

    /** §6.5 step 2: the deck, its head, and the metadata paged around the head. */
    private val cursor = DeckCursor(mediaLibrary)

    /** §6.5 steps 3–4: the image and video windows kept warm around the deck head. */
    private val prefetch = PrefetchWindow(mediaLoader, videoPreloader)

    /** FR-15: successful decisions in order, newest last, capped at [UndoStack.LIMIT]. */
    private val undoStack = UndoStack()

    /**
     * FR-15: cards whose decision write is in flight. While non-empty, [undo]
     * **queues** the tap instead of acting on it ([pendingUndo], WP-9.10) —
     * the deck never rewinds under a completing write, but the tap is not
     * lost: it fires when the write settles. Rewinding
     * under a completing write would re-show the card being decided, and that write's
     * stale-advance guard would discard the advance, leaving a stored verdict behind a
     * card the user then decides a second time; queueing keeps the deck and
     * the stored rows in step (NFR-7) without dropping the gesture.
     *
     * FR-13: a decision naming a card already in this set is dropped too — the UI
     * sends each decision exactly once (its button path refuses a second fly-off),
     * so a repeat is a duplicate delivery and one write per verdict is all it needs.
     */
    private val decideInFlight = mutableSetOf<String>()

    /**
     * FR-15 (WP-9.10): a single queued Undo, set when an [undo] tap lands while a
     * decision write is in flight — the write half of the fly-off window the card
     * is still animating through (the UI holds the same tap for the animation half
     * and forwards it here, see `DeckContent`). The completing [decide] consumes
     * it exactly once: after a successful write it runs the undo as if the tap
     * arrived after the animation (the card comes back), and after a failed write
     * it drops it — the card is already restored by the failure path (NFR-7), so
     * there is nothing to revert and a rewind there would eat an older step.
     * Single slot: extra taps while one is queued are ignored, like extra taps
     * during a single-flight removal.
     */
    private var pendingUndo = false

    /**
     * FR-38: the single-flight hide. While it is up, further Hide taps are ignored
     * (the button is disabled through [SwipeUiState.hiding]) and Undo is held — the
     * same rewind-under-a-completing-write rule as [decideInFlight] (WP-7.2). A
     * decision that lands during the hide write is *not* dropped: it records and
     * advances, and the hide's stale-advance guard discards its own advance (the
     * hide itself stays applied to the library).
     */
    private var hideInFlight = false

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

    /**
     * FR-16: the audio session follows the card on top — unmuting a VIDEO card
     * requests the playback session once, muting releases it, and the request
     * carries across cards. It only observes [uiState], so the
     * [SwipeIntent.ToggleMute] branch below stays a pure state flip.
     */
    private val videoSound =
        VideoSound(
            scope = viewModelScope,
            audio = audioSession,
            state = uiState,
        )

    init {
        // The queue badge tracks the shared delete queue live (FR-10, FR-23).
        viewModelScope.launch {
            decisions
                .deleteQueue()
                .map { queue -> queue.size }
                .collect { size -> _uiState.update { it.copy(queueCount = size) } }
        }
        // FR-29: autoplay rides the state (the `settings` constructor param is in
        // scope here; hard rule 5 keeps the store out of composables). The initial
        // `true` emission equals the state default, so StateFlow dedupes it.
        viewModelScope.launch {
            settings
                .autoplayVideos
                .collect { on -> _uiState.update { it.copy(autoplayVideos = on) } }
        }
        viewModelScope.launch { start() }
    }

    /** The one entry point for user actions (AGENTS.md ViewModel convention). */
    fun onIntent(intent: SwipeIntent) {
        when (intent) {
            is SwipeIntent.Decide -> {
                decide(intent.id, intent.verdict)
            }

            is SwipeIntent.Hide -> {
                hide(intent.id)
            }

            SwipeIntent.Undo -> {
                undo()
            }

            SwipeIntent.ToggleMute -> {
                // FR-16: remembered for this ViewModel's lifetime (the session);
                // the session itself starts from SettingsStore.playVideoSound at
                // construction, never re-read mid-session.
                // [VideoSound] reacts to the flip and steps the audio session
                // up or back for the card on top; nothing to do here.
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
            duplicateSets = started.duplicateSets
            cursor.reset(started.deck)
            sensitiveFilter.settle(cursor)
            prefetch.refresh(cursor.deck, cursor.index, cursor.metadata)
            sensitiveFilter.screenAhead(viewModelScope, cursor)
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
            // FR-15: the write window an Undo tap queues behind (WP-7.2 → WP-9.10).
            // FR-38: mirror the write into `deciding` so the Hide button disables
            // itself — the deck never hides a card whose verdict is being stored.
            decideInFlight += id
            _uiState.update { if (it.deciding) it else it.copy(deciding = true) }
            viewModelScope.launch { commitDecision(id, decision, sessionId) }
        }
    }

    /**
     * The write-and-advance half of [decide], run on [viewModelScope]: persist,
     * then on success advance the deck and fire any Undo the fly-off window
     * queued (WP-9.10); on failure revert the card instead (NFR-7).
     */
    private suspend fun commitDecision(
        id: String,
        decision: Decision,
        sessionId: String,
    ) {
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
                // index advance. The `deciding` flag itself rides the *terminal*
                // state (the failure toast, publish, or finish), so one decision
                // is two emissions: the toggle-up here and the settled state —
                // the same shape as the FR-32 share loading states.
                decideInFlight -= id
            }
        if (!saved) {
            // FR-15 (WP-9.10): the queued Undo dies with the failed write —
            // this revert is bringing the card back, and the decision was
            // never stored, so there is nothing left to undo.
            pendingUndo = false
            _uiState.update {
                it.copy(
                    toast = ToastMessage.SaveFailed,
                    revertToken = it.revertToken + 1,
                    deciding = false,
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
            pageMetadataQuietly { sensitiveFilter.settle(cursor) }
            if (cursor.isExhausted) finish() else publish()
            prefetch.refresh(cursor.deck, cursor.index, cursor.metadata)
            sensitiveFilter.screenAhead(viewModelScope, cursor)
            // FR-15 (WP-9.10): the tap queued during this write fires now —
            // after the advance, so it rewinds exactly this decision and the
            // card comes back. On an exhausted deck it is a no-op: finish()
            // cleared the stack with the run.
            if (pendingUndo) {
                pendingUndo = false
                undo()
            }
        } else {
            // Race loser: the write is durable but the deck already moved, so
            // no deck state follows — clear the FR-38 `deciding` flag here so
            // the Hide button does not stay disabled. The queued Undo goes
            // too (WP-9.10): the deck moved under the tap, and rewinding now
            // would rewind whatever card the race put up, not this one.
            pendingUndo = false
            _uiState.update { if (it.deciding) it.copy(deciding = false) else it }
        }
    }

    /**
     * The hide path (FR-38): flip the card's `hidden` flag through [MediaHider] —
     * immediately, no in-app confirm, **no decision row** — and advance only after
     * the write succeeds (NFR-7). Like a decision the intent names its card: a stale
     * id is dropped, and a result that lands after the card left never advances the
     * deck twice (the same stale-advance guard as [decide]). While the write is in
     * flight ([hideInFlight]) further Hide taps are ignored, Undo is held, and a
     * decision that lands meanwhile records and advances on its own.
     *
     * A pending FR-22 favorite confirm on the card is **cleared at tap time** — the
     * confirm never survives a move, and the hide moves — and travels on the undo
     * step ([UndoEntry.pendingFavorite]) so undo can restore it with the returned
     * card. Success pushes that verdict-less step, advances, and toasts
     * [ToastMessage.Hidden]; failure keeps the card and toasts
     * [ToastMessage.HideFailed].
     */
    private fun hide(id: String) {
        // The card must be on screen (FR-12/FR-13 rule) and nothing may be in flight:
        // one hide at a time, and never a hide under a completing decision write.
        if (cursor.currentId != id || hideInFlight || decideInFlight.isNotEmpty()) return
        val pendingFavorite = _uiState.value.pendingFavoriteDelete.takeIf { it == id }
        hideInFlight = true
        // Clear the favorite confirm synchronously, like ConfirmFavoriteDelete does:
        // the confirm card must not sit over a card that is on its way out.
        _uiState.update {
            it.copy(
                hiding = true,
                toast = if (it.pendingFavoriteDelete == id) null else it.toast,
                pendingFavoriteDelete = if (it.pendingFavoriteDelete == id) null else it.pendingFavoriteDelete,
            )
        }
        viewModelScope.launch {
            val hidden =
                try {
                    mediaHider.setHidden(id, hidden = true) == HideResult.APPLIED
                } catch (cancellation: CancellationException) {
                    throw cancellation // ViewModel teardown, not a failed hide
                } catch (_: Exception) {
                    false
                }
            hideInFlight = false
            if (!hidden) {
                // The card stays exactly where it is (NFR-7); nothing was pushed.
                // Like decide(), the settled state carries the flag down: one hide
                // is two emissions, the toggle-up at the tap and this one.
                _uiState.update { it.copy(hiding = false, toast = ToastMessage.HideFailed) }
                return@launch
            }
            if (cursor.currentId == id) {
                // Stale-advance guard, mirror of decide(): the hide is durable in the
                // library either way, but only the current head advances. publish()
                // and finish() carry `hiding = false` down with the deck state.
                undoStack.push(UndoEntry(id = id, verdict = null, index = cursor.index, pendingFavorite = pendingFavorite))
                cursor.advance()
                pageMetadataQuietly { sensitiveFilter.settle(cursor) }
                if (cursor.isExhausted) finish() else publish(toast = ToastMessage.Hidden)
                prefetch.refresh(cursor.deck, cursor.index, cursor.metadata)
                sensitiveFilter.screenAhead(viewModelScope, cursor)
            } else {
                // Race loser, mirror of decide(): the hide is durable but the deck
                // already moved, so no deck state follows — clear the flag.
                _uiState.update { if (it.hiding) it.copy(hiding = false) else it }
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
     * A tap is likewise **queued** while a [decide] write is in flight
     * ([pendingUndo], WP-9.10): the completing write fires it, so the fly-off
     * window never swallows the gesture. A tap during a [hide] write is still
     * dropped (FR-38): there is no fly-off to return a hidden card from, and the
     * Hide button is disabled while either write is up.
     *
     * FR-38: a step whose [UndoEntry.verdict] is null is a hide — undo unhides the
     * entry's exact id through [MediaHider] instead of removing a decision row, and
     * the card returns in place ([SwipeEvent.ReturnCardInPlace]) with any favorite
     * confirm the hide cleared.
     */
    private fun undo() {
        val sessionId = sessionId ?: return // init has not finished; nothing to undo
        val entry = takeUndoStep() ?: return
        viewModelScope.launch {
            val verdict = entry.verdict
            val undone =
                try {
                    if (verdict == null) {
                        // FR-38: a hide step unhides exactly the id stored in the
                        // entry — never a re-resolved "current" card, the deck may
                        // have moved since — and removes no decision row.
                        mediaHider.setHidden(entry.id, hidden = false) == HideResult.APPLIED
                    } else {
                        undoDecision(entry.id, sessionId)
                        true
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation // ViewModel teardown, not a failed removal
                } catch (_: Exception) {
                    false
                }
            if (!undone) {
                // The removal failed, so the decision is still stored (or the asset
                // is still hidden): the step goes
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
            val pendingFavorite = entry.pendingFavorite
            if (pendingFavorite != null) {
                // FR-38/FR-22: the card returns with the favorite confirm the hide
                // cleared — the deck renders it again (WP-8.9 shape).
                _uiState.update {
                    it.copy(
                        toast = ToastMessage.FavoriteConfirm,
                        pendingFavoriteDelete = pendingFavorite,
                    )
                }
            }
            eventChannel.trySend(
                if (verdict == null) {
                    SwipeEvent.ReturnCardInPlace
                } else {
                    SwipeEvent.ReturnCard(verdict)
                },
            )
            cursor.ensureMetadataLoaded()
            prefetch.refresh(cursor.deck, cursor.index, cursor.metadata)
        }
    }

    /**
     * The Undo tap's guard pass (FR-15, WP-9.10): returns the step to remove, or
     * null when the tap must not proceed. A tap during a decision write is
     * **queued** ([pendingUndo]) — the completing [decide] fires it after the
     * advance (or drops it if the write failed); rewinding under the write is
     * still off-limits, but the tap is no longer dropped. The queue holds one
     * Undo: a second tap while one is queued is ignored. A tap during a hide
     * write stays dropped (FR-38: a hide has no fly-off window to queue for),
     * and an empty stack or the single-flight removal in progress is a no-op
     * (§6.6 canUndo).
     */
    private fun takeUndoStep(): UndoEntry? =
        when {
            pendingUndo -> {
                null
            }

            decideInFlight.isNotEmpty() -> {
                pendingUndo = true
                null
            }

            hideInFlight -> {
                null
            }

            else -> {
                undoStack.popForRemoval()
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
                hiding = false,
                deciding = false,
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
                hiding = false,
                deciding = false,
                loading = false,
            )
        }
    }

    /** The card at [i], or null past the deck's end or before its metadata arrives. */
    private fun cardAt(i: Int): CardState? =
        cursor.itemAt(i)?.let { item ->
            // FR-35: the label rides the card it belongs to; other decks have
            // an empty map, so their cards carry no label.
            CardState.from(item).copy(duplicateSet = duplicateSets[item.id])
        }

    /** §6.5 steps 3–4: leaving the deck hands back the audio session and cancels the still-warm image and video windows. */
    public override fun onCleared() {
        videoSound.release()
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
