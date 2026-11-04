@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.DecisionStore
import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.home.HomeAlbumRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Albums screen's state owner (FR-8, FR-9, §6.5 *Home counts*): the album
 * rows with covers, arriving with PhotoKit's cheap estimate (NFR-4) and each
 * converging to the exact FR-9 count for the screen's filter — the same
 * estimate → exact ladder the Home rows use, over the album path only, so
 * opening Albums never pays for the Shuffle or smart counts (NFR-4).
 *
 * **Refreshes (§6.8):** one collector owns reloads; `latest-wins` via
 * [flatMapLatest] the way [dev.swipe.presentation.home.HomeViewModel] does it,
 * so a burst of `changes()` never runs two album enumerations at once. A
 * failed fetch keeps what the screen already shows; the next refresh retries.
 */
class AlbumsViewModel(
    /** The media-type filter carried from Home (FR-5), fixed for this visit. */
    private val typeFilter: MediaTypeFilter,
    private val library: MediaLibrary,
    private val decisions: DecisionStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlbumsUiState(typeFilter = typeFilter))

    /** The one Albums state: the rows with their converging counts (FR-8). */
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
                .flatMapLatest { load() }
                .collect { rows -> _uiState.update { it.copy(rows = rows) } }
        }
        viewModelScope.launch {
            library.changes().collect { reloads.tryEmit(Unit) }
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
            }
        }
    }

    /**
     * One load: the album rows with estimates first (FR-8, NFR-4), then each
     * row's exact FR-9 count — the deck's own path minus the decided set
     * (§6.5 *Home counts*) — emitted as each one actually moves. A row whose
     * fetch fails keeps its estimate for the next refresh.
     */
    private fun load(): Flow<List<HomeAlbumRow>> =
        flow {
            val albums = attempt { library.albums() } ?: return@flow
            var rows = albums.map { HomeAlbumRow(it.id, it.title, it.coverAssetId, it.count) }
            emit(rows)
            val decided = attempt { decisions.decidedIds() }.orEmpty()
            albums.forEachIndexed { index, album ->
                val ids = attempt { library.assetIds(DeckSource.Album(album.id, album.title), typeFilter) }
                if (ids != null) {
                    val exact = ids.count { it !in decided }
                    if (rows[index].count != exact) {
                        rows = rows.mapIndexed { i, row -> if (i == index) row.copy(count = exact) else row }
                        emit(rows)
                    }
                }
            }
        }

    /** One library read; null on failure so the screen keeps its current rows (§6.8). */
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation // flow teardown, not a failed fetch
        } catch (_: Exception) {
            null
        }
}
