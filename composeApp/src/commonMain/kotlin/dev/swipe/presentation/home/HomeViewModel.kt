@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.usecase.LoadSourcesUseCase
import dev.swipe.domain.usecase.SourceCounts
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Home state owner (§6.5, FR-4, FR-5, FR-9, FR-10, FR-2, FR-7, FR-20, FR-34).
 *
 * **Counts arrive progressively, never block (NFR-4, §6.5 *Home counts*).** The
 * screen mounts with the [HomeUiState] defaults — placeholder rows, no numbers —
 * while [LoadSourcesUseCase] streams albums first, then the Shuffle count, then
 * the five smart counts, then the exact FR-9 refinements. Every arrival merges
 * into the state in place; arrived counts never dip back to a placeholder, so a
 * `changes()` refresh is invisible until the new numbers land.
 *
 * **Refreshes (FR-24, §6.5):** one collector owns reloads. [MediaLibrary.changes]
 * re-reads the authorization (the FR-2 banner signal) and re-runs the counts for
 * the current filter — exactly once per emission, because the refresh is awaited
 * inside the collect body, the same pattern as
 * [dev.swipe.presentation.permission.PermissionViewModel]. The type filter
 * reloads the counts the same way (FR-5), and a late arrival from a superseded
 * filter is discarded so it can never overwrite fresher numbers.
 *
 * **Never called (WP-5.2):** `months(type)` — the Months overlay is FR-39 and
 * belongs to WP-5.3; Home stays off that full-library path.
 */
class HomeViewModel(
    private val library: MediaLibrary,
    private val decisions: DecisionStore,
    private val loadSources: LoadSourcesUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())

    /** The one Home state: filter, rows, stats, queue badge, access (FR-4, FR-10). */
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val events = Channel<HomeEvent>(Channel.UNLIMITED)

    /** One-shot navigation requests for the App host (§6.2). */
    val eventsFlow: Flow<HomeEvent> = events.receiveAsFlow()

    /**
     * Count-reload requests, latest-wins. `replay = 1` doubles as the startup
     * trigger: the [flatMapLatest] collector receives the pending request (the
     * initial `ALL`, or the filter the user picked before it subscribed) the
     * moment it subscribes, and [flatMapLatest] cancels a superseded load so a
     * fast filter flip never runs two enumerations at once.
     */
    private val reloadRequests = MutableSharedFlow<MediaTypeFilter>(replay = 1)

    init {
        reloadRequests.tryEmit(MediaTypeFilter.ALL)

        viewModelScope.launch {
            reloadRequests
                .flatMapLatest { type -> loadSources(type).map { counts -> type to counts } }
                .collect { (type, counts) ->
                    if (_uiState.value.typeFilter == type) {
                        _uiState.value = _uiState.value.withCounts(counts)
                    }
                }
        }

        viewModelScope.launch {
            library.changes().collect {
                applyAccess()
                reloadRequests.tryEmit(_uiState.value.typeFilter)
            }
        }

        viewModelScope.launch {
            decisions.stats().collect { stats ->
                _uiState.update { it.copy(stats = stats) }
            }
        }

        viewModelScope.launch {
            decisions.deleteQueue().collect { queue ->
                _uiState.update { it.copy(queueCount = queue.size) }
            }
        }

        viewModelScope.launch { applyAccess() }
    }

    /** Handle one UI intent (convention: one `StateFlow` in, sealed intents out). */
    fun onIntent(intent: HomeIntent) {
        viewModelScope.launch {
            when (intent) {
                is HomeIntent.SetTypeFilter -> {
                    setTypeFilter(intent.type)
                }

                HomeIntent.OpenQueue -> {
                    events.send(HomeEvent.OpenQueue)
                }

                HomeIntent.OpenSettings -> {
                    events.send(HomeEvent.OpenSettings)
                }

                HomeIntent.OpenSource.Shuffle -> {
                    events.send(HomeEvent.OpenDeck(DeckSource.All, _uiState.value.typeFilter))
                }

                is HomeIntent.OpenSource.Smart -> {
                    events.send(HomeEvent.OpenDeck(DeckSource.Smart(intent.kind), _uiState.value.typeFilter))
                }

                is HomeIntent.OpenSource.Album -> {
                    events.send(
                        HomeEvent.OpenDeck(
                            DeckSource.Album(intent.source.id, intent.source.title),
                            _uiState.value.typeFilter,
                        ),
                    )
                }
            }
        }
    }

    /**
     * FR-5: switching the toggle is visible immediately, the counts under the old
     * filter are dropped to placeholders at once (never stale numbers under the
     * new label), and the rows fill in as the filtered counts arrive.
     */
    private fun setTypeFilter(type: MediaTypeFilter) {
        val current = _uiState.value
        if (current.typeFilter == type) return
        _uiState.value =
            current.copy(
                typeFilter = type,
                shuffleCount = null,
                smartRows = SmartFilter.entries.map { HomeSmartRow(it, null) },
            )
        reloadRequests.tryEmit(type)
    }

    private suspend fun applyAccess() {
        val access: LibraryAccess = runCatching { library.authorization() }.getOrElse { return }
        _uiState.update { it.copy(access = access) }
    }
}

/**
 * Merge one progressive [SourceCounts] arrival into the state: absent members
 * keep what the state already had, arrived members replace it, and the smart
 * rows keep their five-row §5.8 order whatever arrives in which order.
 */
internal fun HomeUiState.withCounts(counts: SourceCounts): HomeUiState =
    copy(
        shuffleCount = counts.shuffleCount ?: shuffleCount,
        smartRows =
            SmartFilter.entries.map { kind ->
                HomeSmartRow(
                    kind = kind,
                    count =
                        counts.smartCounts[kind]
                            ?: smartRows.firstOrNull { it.kind == kind }?.count,
                )
            },
        albumRows = counts.albums ?: albumRows,
    )
