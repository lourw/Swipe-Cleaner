package dev.swipe.presentation.swipe

import androidx.compose.ui.unit.IntSize
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
import dev.swipe.domain.usecase.BuildDeckUseCase
import dev.swipe.domain.usecase.RecordDecisionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Swipe screen's deck pipeline (§6.5 steps 2–3 and 5; FR-12–14, FR-19–20, NFR-7).
 *
 * **Start** (§6.5 step 1): [seedProvider] seeds a session row — the seed lives on the
 * session so the shuffle order survives process death — then [BuildDeckUseCase] builds
 * the deck and metadata loads in pages of 20 (§6.5 step 2), the next page pulled in
 * once the index comes within 5 of the loaded end.
 *
 * **Decisions** (§6.5 step 5): [RecordDecisionUseCase] runs *before* anything moves.
 * Only after the write succeeds does the index advance, the resume position save
 * (FR-20), and the state re-emit. A failed write keeps the card exactly where it is,
 * sets [SwipeUiState.toast] to [ToastMessage.SaveFailed], and bumps
 * [SwipeUiState.revertToken] so the card animates back (NFR-7).
 *
 * **Prefetch** (§6.5 step 3): after every advance the window of current + next 3 is
 * requested from [MediaLoader.prefetch] and ids that left the window are cancelled.
 *
 * Deck exhausted → [SwipeUiState.current] is null; the summary screen (FR-21) is
 * WP-2.2. `Clock` is not injected here: nothing in the core timestamps anything
 * (the stores stamp their own rows), so it joins this constructor with its first use
 * in WP-2.2.
 *
 * This is a plain DI constructor wiring every collaborator of the §6.5 pipeline
 * through Koin; a parameter bag would only hide the graph.
 */
@Suppress("LongParameterList")
class SwipeViewModel(
    private val source: DeckSource,
    private val typeFilter: MediaTypeFilter,
    private val buildDeck: BuildDeckUseCase,
    private val recordDecision: RecordDecisionUseCase,
    private val mediaLibrary: MediaLibrary,
    private val mediaLoader: MediaLoader,
    private val decisions: DecisionStore,
    private val sessionStore: SessionStore,
    private val seedProvider: () -> Long,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SwipeUiState())

    /** The one state flow (AGENTS.md ViewModel convention). */
    val uiState: StateFlow<SwipeUiState> = _uiState.asStateFlow()

    private var sessionId: String? = null
    private var deck: List<String> = emptyList()
    private var index = 0
    private var loadedCount = 0
    private var prefetched: List<String> = emptyList()
    private val metadata = mutableMapOf<String, MediaItem>()

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
        }
    }

    /** Session row with the stored seed, deck build, first metadata page, prefetch, first emit (§6.5). */
    private suspend fun start() {
        val seed = seedProvider()
        sessionId = sessionStore.create(source, typeFilter, seed)
        deck = buildDeck(source, typeFilter, seed)
        ensureMetadataLoaded()
        refreshPrefetch()
        publish()
    }

    /**
     * The decision path (FR-12–14, §6.5 step 5): (a) persist first; (b) on success
     * only, advance, save the resume position (FR-20), and emit; (c) on failure keep
     * the index and revert the card (NFR-7).
     *
     * Concurrent taps (the buttons are always enabled): both intents capture the same
     * card before either write returns and the upsert keeps the decision durable, so
     * after the write each attempt re-checks that the deck head is still the card it
     * recorded — the loser discards its advance. A double tap can never skip an
     * undecided card (NFR-7).
     */
    private fun decide(decision: Decision) {
        val sessionId = sessionId ?: return // init has not finished; there is no card yet
        val id = deck.getOrNull(index) ?: return // exhausted: current stays null (§6.6)
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
            // Stale-advance guard: the deck head moved while this write was in flight,
            // so another intent already advanced past [id]; drop this advance (the
            // decision itself stays durable).
            if (deck.getOrNull(index) != id) return@launch
            index += 1
            // The resume position is a best-effort hint (FR-20): the decision itself is
            // already durable, so a failed position write must not revert the card.
            try {
                sessionStore.updatePosition(sessionId, index)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Ignored on purpose; see above.
            }
            publish()
            ensureMetadataLoaded()
            refreshPrefetch()
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

    /** Emit the deck slice; a successful decision also clears any earlier toast. */
    private fun publish() {
        _uiState.update {
            it.copy(
                current = cardAt(index),
                next = cardAt(index + 1),
                progress = Progress(reviewed = index, total = deck.size),
                toast = null,
            )
        }
    }

    /** The card at [i], or null past the deck's end or before its metadata arrives. */
    private fun cardAt(i: Int): CardState? = deck.getOrNull(i)?.let { metadata[it] }?.let(CardState::from)

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
         * the identical value, so this is the one shared constant.
         */
        val PREFETCH_TARGET_PX = IntSize(Int.MAX_VALUE, Int.MAX_VALUE)
    }
}
