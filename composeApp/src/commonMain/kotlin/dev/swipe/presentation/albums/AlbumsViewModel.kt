@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.MediaLibrary
import dev.swipe.data.ShuffleExclusionStore
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.usecase.SourceCountsUseCase
import dev.swipe.presentation.ListStatus
import dev.swipe.presentation.home.HomeAlbumRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Albums screen's state owner (FR-8, FR-9, FR-37, §6.5 *Home counts*): the
 * album rows with covers, each showing the exact FR-9 count of its own deck
 * through [SourceCountsUseCase.albums] — the one count path (WP-8.12), over
 * the album path only, so opening Albums never pays for the Shuffle or smart
 * counts (NFR-4). Final-or-placeholder: the rows arrive with placeholders
 * first, then each number lands exactly once; no estimate is ever displayed.
 *
 * **Exclude from Shuffle (FR-37):** every row carries a switch backed by
 * [ShuffleExclusionStore]; [AlbumsUiState.excludedAlbumIds] is driven by the
 * store's Flow, so a write that fails leaves the switch exactly where it was
 * (NFR-7). Exclusion reaches only the Shuffle source — tapping a row still
 * opens that album's full deck — so toggling never reloads this list.
 *
 * **Refreshes (§6.8):** one collector owns reloads; `latest-wins` via
 * [flatMapLatest] the way [dev.swipe.presentation.home.HomeViewModel] does it,
 * so a burst of `changes()` never runs two album enumerations at once. A
 * failed refresh keeps what the screen already shows; the next refresh
 * retries. A scan that fails while the screen still has nothing to show
 * moves it to [ListStatus.Error] — the failure explains itself with a retry
 * (WP-9.6, FR-8, §5.8).
 */
class AlbumsViewModel(
    /** The media-type filter carried from Home (FR-5), fixed for this visit. */
    private val typeFilter: MediaTypeFilter,
    private val library: MediaLibrary,
    /** FR-37 exclusions: the switch states and where toggles are written. */
    private val exclusions: ShuffleExclusionStore,
    /** The one count path (FR-9, WP-8.12): each row's number is its deck's size. */
    private val sourceCounts: SourceCountsUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlbumsUiState(typeFilter = typeFilter))

    /** The one Albums state: the rows with their exact counts (FR-8). */
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<AlbumsEvent>(Channel.UNLIMITED)

    /** One-shot navigation requests for the App host (§6.2). */
    val events: Flow<AlbumsEvent> = eventChannel.receiveAsFlow()

    /** Reload requests, latest-wins; `replay = 1` doubles as the startup trigger. */
    private val reloads = MutableSharedFlow<Unit>(replay = 1)

    init {
        reloads.tryEmit(Unit)
        viewModelScope.launch {
            reloads
                .flatMapLatest {
                    sourceCounts.albums(typeFilter)
                        .onEach { counted ->
                            _uiState.update { state ->
                                // Final-or-placeholder merge (§6.5): an arrived
                                // count is exact, an absent one keeps the row's
                                // held number, so a refresh never dips a settled
                                // row back. The scan answering moves the screen
                                // from Loading to Ready or Empty (WP-9.6).
                                val rows =
                                    counted.rows.map { album ->
                                        HomeAlbumRow(
                                            id = album.id,
                                            title = album.title,
                                            coverAssetId = album.coverAssetId,
                                            count =
                                                counted.countFor(album.id)
                                                    ?: state.rows.firstOrNull { it.id == album.id }?.count,
                                        )
                                    }
                                state.copy(
                                    status = if (rows.isEmpty()) ListStatus.Empty else ListStatus.Ready,
                                    rows = rows,
                                )
                            }
                        }
                        .catch {
                            // WP-9.6 (FR-8): a scan that cannot answer explains
                            // itself — but only while the screen still has
                            // nothing to show. A failed refresh keeps the rows
                            // for the next changes() retry (§6.8).
                            _uiState.update { state ->
                                if (state.status == ListStatus.Loading) {
                                    state.copy(status = ListStatus.Error)
                                } else {
                                    state
                                }
                            }
                        }
                }
                .collect {
                    // onEach and catch above are the whole collector.
                }
        }
        viewModelScope.launch {
            library.changes().collect { reloads.tryEmit(Unit) }
        }
        viewModelScope.launch {
            exclusions.excludedAlbumIdsFlow().collect { ids ->
                _uiState.update { it.copy(excludedAlbumIds = ids) }
            }
        }
    }

    /** Handle one UI intent (convention: one `StateFlow` in, sealed intents out). */
    fun onIntent(intent: AlbumsIntent) {
        viewModelScope.launch {
            when (intent) {
                is AlbumsIntent.OpenAlbum -> {
                    eventChannel.trySend(
                        AlbumsEvent.OpenDeck(
                            source = DeckSource.Album(intent.row.id, intent.row.title),
                            typeFilter = typeFilter,
                        ),
                    )
                }

                is AlbumsIntent.ToggleShuffleExclusion -> {
                    // The state comes back from the store's Flow, so a failed
                    // write changes nothing on screen (NFR-7).
                    val excludedNow = intent.row.id in _uiState.value.excludedAlbumIds
                    attempt { exclusions.setExcluded(intent.row.id, !excludedNow) }
                }

                AlbumsIntent.Retry -> {
                    // WP-9.6: from the error state, back to the loading
                    // message and one fresh scan (FR-8).
                    _uiState.update { it.copy(status = ListStatus.Loading) }
                    reloads.tryEmit(Unit)
                }
            }
        }
    }

    /** One store write; a failure leaves the switch where the store says it is (NFR-7). */
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation // not a failed write
        } catch (_: Exception) {
            null
        }
}
