@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.months

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.model.MonthBucket
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
 * The By month screen's state owner (FR-7): the month buckets for
 * [typeFilter], newest first. The scan runs when this screen opens — never
 * at Home startup (NFR-4, FR-7) — and once per [MediaLibrary.changes]
 * emission, latest-wins via [flatMapLatest] like
 * [dev.swipe.presentation.home.HomeViewModel], so a burst of changes never
 * runs two scans at once.
 *
 * The buckets show [MonthBucket.count] — the items created in each month for
 * the filter (§6.5 *Home counts* buys its per-row FR-9 refinement for the
 * Home rows only; re-counting every month against `decidedIds()` would mean
 * one fetch per bucket for no spec'd number). A failed scan keeps what the
 * screen shows; the next refresh retries.
 */
class MonthsViewModel(
    /** The media-type filter carried from Home (FR-5), fixed for this visit. */
    private val typeFilter: MediaTypeFilter,
    private val library: MediaLibrary,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MonthsUiState(typeFilter = typeFilter))

    /** The one By month state: the buckets, newest first (FR-7). */
    val uiState: StateFlow<MonthsUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<MonthsEvent>(Channel.UNLIMITED)

    /** One-shot navigation requests for the App host (§6.2). */
    val events: Flow<MonthsEvent> = eventChannel.receiveAsFlow()

    /** Reload requests, latest-wins; `replay = 1` doubles as the startup trigger. */
    private val reloads = MutableSharedFlow<Unit>(replay = 1)

    init {
        reloads.tryEmit(Unit)
        viewModelScope.launch {
            reloads
                .flatMapLatest { load() }
                .collect { buckets -> _uiState.update { it.copy(buckets = buckets) } }
        }
        viewModelScope.launch {
            library.changes().collect { reloads.tryEmit(Unit) }
        }
    }

    /** Handle one UI intent (convention: one `StateFlow` in, sealed intents out). */
    fun onIntent(intent: MonthsIntent) {
        viewModelScope.launch {
            when (intent) {
                is MonthsIntent.OpenMonth -> {
                    eventChannel.trySend(
                        MonthsEvent.OpenDeck(
                            source = DeckSource.Month(intent.bucket.year, intent.bucket.month),
                            typeFilter = typeFilter,
                        ),
                    )
                }
            }
        }
    }

    /** One scan; a failed one emits nothing, so the screen keeps its current rows. */
    private fun load(): Flow<List<MonthBucket>> =
        flow {
            val buckets =
                try {
                    library.months(typeFilter)
                } catch (cancellation: CancellationException) {
                    throw cancellation // flow teardown, not a failed scan
                } catch (_: Exception) {
                    return@flow
                }
            emit(buckets)
        }
}
