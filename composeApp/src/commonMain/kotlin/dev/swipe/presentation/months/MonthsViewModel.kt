@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.months

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.MediaLibrary
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.domain.usecase.SourceCountsUseCase
import dev.swipe.presentation.ListStatus
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
 * The By month screen's state owner (FR-7): the month rows for [typeFilter],
 * newest first. The scan runs when this screen opens — never at Home startup
 * (NFR-4, FR-7) — and once per [MediaLibrary.changes] emission, latest-wins
 * via [flatMapLatest] like [dev.swipe.presentation.home.HomeViewModel], so a
 * burst of changes never runs two scans at once.
 *
 * Each row's count is the size of the `DeckSource.Month` deck the row opens —
 * [SourceCountsUseCase.months], decided items subtracted (FR-9, WP-8.12) —
 * arriving final or placeholder: the bucket list shows first with placeholders,
 * then each row settles exactly once. Before WP-8.12 this screen showed the
 * raw scan count, which over-reported every bucket holding a decided item.
 * A failed scan keeps what the screen shows; the next refresh retries. While
 * the screen is still [ListStatus.Loading] a failure moves it to
 * [ListStatus.Error] — the scan explains itself with a retry, and while it
 * runs the screen shows its loading message (WP-9.6, FR-7, NFR-4, §5.8).
 */
class MonthsViewModel(
    /** The media-type filter carried from Home (FR-5), fixed for this visit. */
    private val typeFilter: MediaTypeFilter,
    private val library: MediaLibrary,
    /** The one count path (FR-9, WP-8.12): each row's number is its deck's size. */
    private val sourceCounts: SourceCountsUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MonthsUiState(typeFilter = typeFilter))

    /** The one By month state: the rows, newest first (FR-7). */
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
                .flatMapLatest {
                    sourceCounts
                        .months(typeFilter)
                        .onEach { counted ->
                            _uiState.update { state ->
                                // Final-or-placeholder merge (§6.5): an arrived
                                // count is exact, an absent one keeps the row's
                                // held number, so a refresh never dips a settled
                                // row back. The scan answering moves the screen
                                // from Loading to Ready or Empty (WP-9.6).
                                val rows =
                                    counted.rows.map { bucket ->
                                        MonthRow(
                                            bucket = bucket,
                                            count =
                                                counted.countFor(SourceCountsUseCase.monthKey(bucket))
                                                    ?: state.rows.firstOrNull { it.bucket == bucket }?.count,
                                        )
                                    }
                                state.copy(
                                    status = if (rows.isEmpty()) ListStatus.Empty else ListStatus.Ready,
                                    rows = rows,
                                )
                            }
                        }.catch {
                            // WP-9.6 (FR-7): a scan that cannot answer explains
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
                }.collect {
                    // onEach and catch above are the whole collector.
                }
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

                MonthsIntent.Retry -> {
                    // WP-9.6: from the error state, back to the loading
                    // message and one fresh scan (FR-7).
                    _uiState.update { it.copy(status = ListStatus.Loading) }
                    reloads.tryEmit(Unit)
                }
            }
        }
    }
}
