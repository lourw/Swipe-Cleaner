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
 * The Home state owner (§6.5, FR-2, FR-4, FR-5, FR-7, FR-9, FR-10).
 *
 * **Counts arrive progressively, never block (NFR-4, §6.5 *Home counts*).** The
 * screen mounts with the [HomeUiState] defaults — placeholder rows, no numbers —
 * while [LoadSourcesUseCase] streams albums first, then the Shuffle count, then
 * the five smart counts, then the exact FR-9 refinements. Every arrival merges
 * into the state in place; a `changes()` refresh never flickers a refined row
 * back to an estimate ([exactRows]), so the screen updates once per real change.
 *
 * **Refreshes (§6.5, §6.8):** one collector owns reloads. [MediaLibrary.changes]
 * re-reads the authorization (the FR-2 banner signal) and re-runs the counts for
 * the current filter — exactly once per emission, because the refresh is awaited
 * inside the collect body, the same pattern as
 * [dev.swipe.presentation.permission.PermissionViewModel]. The type filter
 * reloads the counts the same way (FR-5), and a late arrival from a superseded
 * filter is discarded so it can never overwrite fresher numbers.
 *
 * **Never called (WP-5.2):** `months(type)` — the By-month screen is FR-7 and
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

    private val eventChannel = Channel<HomeEvent>(Channel.UNLIMITED)

    /** One-shot navigation requests for the App host (§6.2). */
    val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

    /**
     * Which rows currently show their FR-9-exact count (§6.5). A `changes()`
     * re-run streams raw estimates again; rows this mask marks exact keep their
     * refined number until the re-run's own refinement replaces it, so the screen
     * never flickers exact → estimate → exact. Reset when the type filter drops
     * every count.
     */
    private var exactRows = HomeExactRows()

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
                        _uiState.value = _uiState.value.withCounts(counts, exactRows)
                        exactRows = exactRows.advance(counts)
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
                    eventChannel.trySend(HomeEvent.OpenQueue)
                }

                HomeIntent.OpenSettings -> {
                    eventChannel.trySend(HomeEvent.OpenSettings)
                }

                HomeIntent.OpenSource.Shuffle -> {
                    eventChannel.trySend(HomeEvent.OpenDeck(DeckSource.All, _uiState.value.typeFilter))
                }

                is HomeIntent.OpenSource.Smart -> {
                    eventChannel.trySend(HomeEvent.OpenDeck(DeckSource.Smart(intent.kind), _uiState.value.typeFilter))
                }

                is HomeIntent.OpenSource.Album -> {
                    eventChannel.trySend(
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
     * filter are dropped to placeholders at once — album rows keep their identity
     * but lose the number, so there are never stale counts under a new label —
     * and the rows fill in as the filtered counts arrive.
     */
    private fun setTypeFilter(type: MediaTypeFilter) {
        val current = _uiState.value
        if (current.typeFilter == type) return
        _uiState.value =
            current.copy(
                typeFilter = type,
                shuffleCount = null,
                smartRows = SmartFilter.entries.map { HomeSmartRow(it, null) },
                albumRows = current.albumRows.map { it.copy(count = null) },
            )
        exactRows = HomeExactRows()
        reloadRequests.tryEmit(type)
    }

    private suspend fun applyAccess() {
        val access: LibraryAccess = runCatching { library.authorization() }.getOrElse { return }
        _uiState.update { it.copy(access = access) }
    }
}

/**
 * Which Home rows currently show their FR-9-exact count (§6.5): accumulated from
 * the exactness flags [SourceCounts] carries, so a `changes()` re-run's raw
 * arrivals can be told apart from refinements.
 */
internal data class HomeExactRows(
    val shuffle: Boolean = false,
    val smart: Set<SmartFilter> = emptySet(),
    val albums: Set<String> = emptySet(),
)

/** Fold one arrival's exactness flags into the mask. */
internal fun HomeExactRows.advance(counts: SourceCounts): HomeExactRows =
    HomeExactRows(
        shuffle = shuffle || counts.shuffleExact,
        smart = smart + counts.exactSmart,
        albums = albums + counts.exactAlbumIds,
    )

/**
 * Merge one progressive [SourceCounts] arrival into the state: absent members
 * keep what the state already had, arrived members replace it, and the smart
 * rows keep their five-row §5.8 order whatever arrives in which order. Rows the
 * [exact] mask marks FR-9-exact never regress to a raw arrival — a `changes()`
 * re-run's estimates wait behind the refined numbers until its own refinement
 * lands (§6.5).
 */
internal fun HomeUiState.withCounts(
    counts: SourceCounts,
    exact: HomeExactRows,
): HomeUiState =
    copy(
        shuffleCount =
            counts.shuffleCount?.let { incoming ->
                if (exact.shuffle && !counts.shuffleExact) shuffleCount else incoming
            } ?: shuffleCount,
        smartRows =
            SmartFilter.entries.map { kind ->
                val held = smartRows.firstOrNull { it.kind == kind }?.count
                HomeSmartRow(
                    kind = kind,
                    count =
                        counts.smartCounts[kind]?.let { incoming ->
                            if (kind in exact.smart && kind !in counts.exactSmart) held else incoming
                        } ?: held,
                )
            },
        albumRows =
            counts.albums?.map { album ->
                val held = albumRows.firstOrNull { it.id == album.id }
                if (held != null && album.id in exact.albums && album.id !in counts.exactAlbumIds) {
                    held
                } else {
                    HomeAlbumRow(album.id, album.title, album.coverAssetId, album.count)
                }
            } ?: albumRows,
    )
