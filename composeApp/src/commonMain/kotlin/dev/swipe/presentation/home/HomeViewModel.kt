@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.data.ShuffleExclusionStore
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.SmartFilter
import dev.swipe.domain.usecase.SourceCounts
import dev.swipe.domain.usecase.SourceCountsUseCase
import dev.swipe.presentation.DecidedSetWatch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Home state owner (§6.5, FR-2, FR-4, FR-5, FR-7, FR-9, FR-10).
 *
 * **Counts arrive progressive, final-or-placeholder (NFR-4, §6.5 *Home
 * counts*).** The screen mounts with the [HomeUiState] defaults — placeholder
 * rows, no numbers — while [SourceCountsUseCase] streams the row identity, then
 * each row's exact FR-9 deck size. No row ever shows a number that is about to
 * be replaced by a different one: the raw-estimate stage is gone (WP-8.12), so
 * a type switch drops the counts to placeholders and each row fills in exactly
 * once — no jump-to-the-total flicker (FR-5). Every arrival merges into the
 * state in place, and a `changes()` refresh never dips a settled row back
 * because its re-run carries only final numbers.
 *
 * **Refreshes (§6.5, §6.8):** one collector owns reloads. [MediaLibrary.changes]
 * re-reads the authorization (the FR-2 banner signal) and re-runs the counts for
 * the current filter — exactly once per emission, because the refresh is awaited
 * inside the collect body, the same pattern as
 * [dev.swipe.presentation.permission.PermissionViewModel]. The type filter
 * reloads the counts the same way (FR-5), and a late arrival from a superseded
 * filter is discarded so it can never overwrite fresher numbers. A `Resumed`
 * intent re-checks the decided set with [DecidedSetWatch] and re-runs the
 * counts once when it moved — an FR-30 reset landing on Home without a library
 * change — and costs nothing on a resume with no change.
 *
 * **Never called (WP-5.2):** `months(type)` — the By-month screen is FR-7 and
 * belongs to WP-5.3; Home stays off that full-library path.
 */
class HomeViewModel(
    private val library: MediaLibrary,
    private val decisions: DecisionStore,
    /** The one count path (FR-9, WP-8.12): every row's number is its deck's size. */
    private val sourceCounts: SourceCountsUseCase,
    /** FR-37: exclusions change which items the Shuffle row's deck holds. */
    private val exclusions: ShuffleExclusionStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())

    /** The one Home state: filter, rows, stats, queue badge, access (FR-4, FR-10). */
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<HomeEvent>(Channel.UNLIMITED)

    /** One-shot navigation requests for the App host (§6.2). */
    val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

    /**
     * Count-reload requests, latest-wins. `replay = 1` doubles as the startup
     * trigger: the [flatMapLatest] collector receives the pending request (the
     * initial `ALL`, or the filter the user picked before it subscribed) the
     * moment it subscribes, and [flatMapLatest] cancels a superseded load so a
     * fast filter flip never runs two enumerations at once.
     */
    private val reloadRequests = MutableSharedFlow<MediaTypeFilter>(replay = 1)

    /** FR-30: the resume check — counts re-run only when the decided set moved. */
    private val decidedWatch = DecidedSetWatch(decisions)

    init {
        reloadRequests.tryEmit(MediaTypeFilter.ALL)

        viewModelScope.launch {
            reloadRequests
                .flatMapLatest { type -> sourceCounts(type).map { counts -> type to counts } }
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
            // FR-37: an Albums toggle re-runs the counts so the Shuffle row
            // keeps matching the deck its row opens (FR-9). `drop(1)` skips the
            // Flow's initial snapshot — the startup load already reads the
            // store — and the re-run carries only final numbers, so the row
            // updates once for the real change (§6.5).
            exclusions
                .excludedAlbumIdsFlow()
                .drop(1)
                .collect {
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

            HomeIntent.OpenAlbums -> {
                eventChannel.trySend(HomeEvent.OpenAlbums(_uiState.value.typeFilter))
            }

            HomeIntent.OpenMonths -> {
                eventChannel.trySend(HomeEvent.OpenMonths(_uiState.value.typeFilter))
            }

            HomeIntent.Resumed -> {
                // FR-30: back on Home (or foregrounded) after a reset, a
                // finished deck, a commit or a queue edit — re-run the counts
                // for the current filter once, and only when the decided set
                // actually changed. The re-run carries only final numbers, so
                // settled rows never dip to the placeholder (§6.5).
                viewModelScope.launch {
                    if (decidedWatch.changedSinceLastCheck()) {
                        reloadRequests.tryEmit(_uiState.value.typeFilter)
                    }
                }
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
        reloadRequests.tryEmit(type)
    }

    private suspend fun applyAccess() {
        val access: LibraryAccess = runCatching { library.authorization() }.getOrElse { return }
        _uiState.update { it.copy(access = access) }
    }
}

/**
 * Merge one progressive [SourceCounts] arrival into the state: an arrived count
 * is final (FR-9-exact, WP-8.12), an absent one keeps what the state already
 * had — the placeholder before the first arrival, the held final number across
 * a `changes()` re-run — so a row updates once per real change and never dips
 * (§6.5 *Home counts*). The smart rows keep their five-row §5.8 order whatever
 * order the counts arrive in; an album row's number is [SourceCounts.albumCounts]
 * for its id — the library's estimate in [AlbumSource] is never displayed.
 */
internal fun HomeUiState.withCounts(counts: SourceCounts): HomeUiState =
    copy(
        shuffleCount = counts.shuffleCount ?: shuffleCount,
        smartRows =
            SmartFilter.entries.map { kind ->
                HomeSmartRow(
                    kind = kind,
                    count = counts.smartCounts[kind] ?: smartRows.firstOrNull { it.kind == kind }?.count,
                )
            },
        albumRows =
            counts.albums?.map { album ->
                HomeAlbumRow(
                    id = album.id,
                    title = album.title,
                    coverAssetId = album.coverAssetId,
                    count = counts.albumCounts[album.id] ?: albumRows.firstOrNull { it.id == album.id }?.count,
                )
            } ?: albumRows,
    )
