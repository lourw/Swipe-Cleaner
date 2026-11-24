package dev.swipe.presentation.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.DecisionStore
import dev.swipe.data.Haptics
import dev.swipe.data.MediaLibrary
import dev.swipe.data.MediaSizing
import dev.swipe.domain.model.MediaItem
import dev.swipe.domain.model.StorageEstimate
import dev.swipe.domain.usecase.CommitDeletionsUseCase
import dev.swipe.domain.usecase.CommitOutcome
import dev.swipe.domain.usecase.InAppDeleteConfirmation
import dev.swipe.domain.usecase.PruneQueueUseCase
import dev.swipe.domain.usecase.RestoreFromQueueUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Review Queue screen (FR-23–28, §6.7): the live pending-delete list, restore,
 * and the two-step batch delete.
 *
 * **Queue** (FR-23, FR-28): [items] mirrors [DecisionStore.deleteQueue] — newest
 * decision first — re-emitted live, with [QueueUiState.tiles] carrying the grid
 * metadata fetched alongside every change in pages of 20 (§6.5 step 2). On load
 * [PruneQueueUseCase] first drops ids PhotoKit no longer returns, silently, and
 * every `MediaLibrary.changes()` emission re-runs the prune (WP-3.2), so an asset
 * deleted in the Photos app disappears without an app restart.
 *
 * **Restore** (FR-24): [QueueIntent.Restore] records KEEP via
 * [RestoreFromQueueUseCase]; the queue flow drops the row, and an open viewer for
 * that id closes with it. A failed write emits [QueueEvent.RestoreFailed].
 *
 * **Delete** (FR-25–27, NFR-5, §6.7): [QueueIntent.RequestDelete] only raises the
 * in-app confirm dialog; [QueueIntent.ConfirmDelete] is the *only* place an
 * [InAppDeleteConfirmation] is built — from the whole queue at tap time (FR-25) —
 * and the only path into [CommitDeletionsUseCase]. While the commit runs,
 * [QueueUiState.deleting] blocks re-confirmation, so one batch is one
 * `MediaDeleter.delete` call (FR-25). Outcomes: success fires the
 * `notificationSuccess` haptic and emits [QueueEvent.ShowDeleteSuccess] (the UI
 * navigates; the ViewModel never does); failure emits
 * [QueueEvent.ShowDeleteError] and the queue stays as it was; a cancelled iOS
 * dialog is silent (FR-27).
 *
 * **Size estimate** (FR-34, WP-8.4): [QueueIntent.RequestDelete] also kicks off
 * [MediaSizing.estimateBytes] over the queue on the dialog's behalf — a side
 * channel that gates nothing. The result lands in [QueueUiState.deleteSize]
 * whenever it finishes (a throw or an unavailable read renders no line, R3),
 * and the delete path never consults it: [QueueIntent.ConfirmDelete] is exactly
 * the FR-25 flow with or without an estimate.
 */
class QueueViewModel(
    private val commitDeletions: CommitDeletionsUseCase,
    private val pruneQueue: PruneQueueUseCase,
    private val restoreFromQueue: RestoreFromQueueUseCase,
    private val mediaSizing: MediaSizing,
    private val decisions: DecisionStore,
    private val mediaLibrary: MediaLibrary,
    private val haptics: Haptics,
) : ViewModel() {
    private val _uiState = MutableStateFlow(QueueUiState())

    /** The one state flow (AGENTS.md ViewModel convention). */
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<QueueEvent>(Channel.UNLIMITED)

    /**
     * One-shot ViewModel → UI events (§6.6): navigation and toasts ride a
     * `Channel`, so a late collector never replays a stale success or error.
     */
    val events: Flow<QueueEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            // FR-28: prune vanished ids *before* first collect so the screen never
            // shows a row it just dropped. A failed prune is silent, and must not
            // kill the queue collector below.
            try {
                pruneQueue()
            } catch (cancellation: CancellationException) {
                throw cancellation // ViewModel teardown, not a failed prune
            } catch (_: Exception) {
                // FR-28 keeps pruning silent; the next load retries.
            }
            // FR-23: the newest-first order is the store's query, live. The grid
            // metadata is fetched BEFORE publishing so `items` and `tiles` change
            // in one update: the screen can never render a count whose tiles are
            // a step behind (NFR-8), and no half-updated state escapes the VM.
            decisions.deleteQueue().collect { ids ->
                val byId = tileMetadata(ids)
                _uiState.update { state ->
                    state.copy(
                        items = ids,
                        tiles =
                            ids.map { id ->
                                val item = byId[id]
                                QueueTile(
                                    id = id,
                                    type = item?.type,
                                    createdAt = item?.createdAt,
                                    durationMs = item?.durationMs,
                                )
                            },
                    )
                }
            }
        }
        // FR-28 (WP-3.2): queued assets can vanish outside the app — deleted in
        // Photos, or lost with an iCloud library change. Every `changes()`
        // emission re-runs the silent prune so the list converges without a
        // restart. While a commit runs the queue belongs to it (FR-25): pruning
        // mid-batch would race the `markDeleted` write, so those emissions are
        // skipped — the next one or the next load prunes again anyway.
        viewModelScope.launch {
            mediaLibrary.changes().collect {
                if (_uiState.value.deleting) return@collect
                try {
                    pruneQueue()
                } catch (cancellation: CancellationException) {
                    throw cancellation // ViewModel teardown, not a failed prune
                } catch (_: Exception) {
                    // FR-28 keeps pruning silent; the next change retries.
                }
            }
        }
    }

    /**
     * FR-23 / NFR-8: metadata for [ids] in pages of 20 (§6.5 step 2, NFR-4 — the
     * same cap as [PruneQueueUseCase]). A failed fetch is silent — tiles render
     * without date/duration metadata rather than dropping rows — and the next
     * queue change retries.
     */
    private suspend fun tileMetadata(ids: List<String>): Map<String, MediaItem> =
        try {
            ids
                .chunked(TILE_META_PAGE_SIZE)
                .flatMap { page -> mediaLibrary.items(page) }
                .associateBy { it.id }
        } catch (cancellation: CancellationException) {
            throw cancellation // ViewModel teardown, not a failed fetch
        } catch (_: Exception) {
            emptyMap() // metadata-less tiles; the grid still shows the queue
        }

    /** The one entry point for user actions (AGENTS.md ViewModel convention). */
    fun onIntent(intent: QueueIntent) {
        when (intent) {
            is QueueIntent.Open -> open(intent.id)
            QueueIntent.Close -> close()
            is QueueIntent.Restore -> restore(intent.id)
            QueueIntent.RequestDelete -> requestDelete()
            QueueIntent.ConfirmDelete -> confirmDelete()
            QueueIntent.CancelConfirm -> cancelConfirm()
        }
    }

    /**
     * The FR-34 estimate run for the dialog currently up; cancelled when the
     * dialog closes so a late result can never decorate a later one. Null when
     * no sizing is in flight.
     */
    private var sizingJob: Job? = null

    /** FR-24: open a queued item full-screen; ids the queue no longer has are ignored. */
    private fun open(id: String) {
        val state = _uiState.value
        if (state.deleting || id !in state.items) return
        _uiState.update { it.copy(selected = id) }
    }

    /** Back/Close: drop the viewer and the confirm dialog; data is untouched. */
    private fun close() {
        sizingJob?.cancel()
        sizingJob = null
        _uiState.update { it.copy(selected = null, confirming = false, deleteSize = null) }
    }

    /** FR-24: restore one item; the deleteQueue re-emit removes it from [QueueUiState.items]. */
    private fun restore(id: String) {
        val state = _uiState.value
        if (id !in state.items) return // only queued ids exist for the user (mirrors open())
        if (state.deleting) return // no store writes while a commit owns the queue
        viewModelScope.launch {
            try {
                restoreFromQueue(id)
            } catch (cancellation: CancellationException) {
                throw cancellation // ViewModel teardown, not a failed restore
            } catch (_: Exception) {
                // The item stays queued (NFR-7's convention); tell the user.
                eventChannel.trySend(QueueEvent.RestoreFailed)
                return@launch
            }
            if (_uiState.value.selected == id) {
                _uiState.update { it.copy(selected = null) }
            }
        }
    }

    /**
     * NFR-5: the "Delete N items" tap raises the dialog and stops there — no
     * confirmation token exists yet, so no delete is even expressible.
     *
     * FR-34: raising the dialog also starts the size estimate for it, over the
     * queue as it stands now. The run is fire-and-forget — nothing here awaits
     * it, and its result only ever fills in [QueueUiState.deleteSize].
     */
    private fun requestDelete() {
        val state = _uiState.value
        if (state.deleting || state.items.isEmpty()) return
        _uiState.update { it.copy(confirming = true, result = null, deleteSize = null) }
        sizeQueue(state.items)
    }

    /**
     * FR-34: asks [MediaSizing] for [ids] and publishes whatever comes back.
     * A throw is not an event: it is `Unavailable`, and the line is omitted
     * (R3 — sizing never reaches the user as an error). A result that lands
     * after the dialog closed updates state the dialog no longer renders;
     * reopening starts a fresh run, whose job cancels this one.
     */
    private fun sizeQueue(ids: List<String>) {
        sizingJob?.cancel()
        sizingJob =
            viewModelScope.launch {
                val estimate =
                    runCatching { mediaSizing.estimateBytes(ids) }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            StorageEstimate.Unavailable
                        }
                _uiState.update { it.copy(deleteSize = estimate) }
            }
    }

    /**
     * The dialog's Delete: the sole construction site of [InAppDeleteConfirmation]
     * (§6.7). The token carries the whole queue as it stands at the tap (FR-25);
     * [CommitDeletionsUseCase] intersects it with the current queue and calls
     * PhotoKit once. Nothing here consults the FR-34 estimate — the delete never
     * waits on sizing (R3).
     */
    private fun confirmDelete() {
        val state = _uiState.value
        // The dialog is the gate (NFR-5), and deleting blocks re-confirmation
        // (FR-25). An empty queue cannot have raised the dialog; a prune while it
        // was up is handled by the use case's empty intersection.
        if (!state.confirming || state.deleting) return
        val confirmation = InAppDeleteConfirmation(state.items)
        sizingJob?.cancel()
        sizingJob = null
        _uiState.update { it.copy(confirming = false, deleting = true, result = null, deleteSize = null) }
        viewModelScope.launch {
            val outcome =
                runCatching { commitDeletions(confirmation) }
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        // A throw from the queue re-read or PhotoKit itself follows the
                        // §6.7 error branch: the queue is unchanged, the user hears why.
                        CommitOutcome.Error(error.message ?: "Delete failed")
                    }
            when (outcome) {
                is CommitOutcome.Success -> {
                    // FR-26: haptic, state, then the navigation event (§5.6, §6.4).
                    // A zero-count success — every id pruned while the dialog was
                    // up, so PhotoKit was never asked for anything — reports
                    // neither haptic nor success screen: there is nothing new to
                    // celebrate, and the queue's own empty state is the feedback.
                    // A partial batch (WP-7.6) reports both counts: the not-found
                    // ids stay queued and the success screen says so.
                    val reported = outcome.deleted > 0 || outcome.notFound > 0
                    if (reported) {
                        haptics.notificationSuccess()
                    }
                    _uiState.update {
                        it.copy(
                            deleting = false,
                            selected = null,
                            result = QueueDeleteResult.Deleted(outcome.deleted, outcome.notFound),
                        )
                    }
                    if (reported) {
                        eventChannel.trySend(QueueEvent.ShowDeleteSuccess(outcome.deleted, outcome.notFound))
                    }
                }

                CommitOutcome.Cancelled -> {
                    // FR-27: silent, queue exactly as it was; result stays null.
                    _uiState.update { it.copy(deleting = false, result = null) }
                }

                is CommitOutcome.Error -> {
                    _uiState.update {
                        it.copy(
                            deleting = false,
                            result = QueueDeleteResult.Failed(outcome.message),
                        )
                    }
                    eventChannel.trySend(QueueEvent.ShowDeleteError(outcome.message))
                }
            }
        }
    }

    /** NFR-5: the dialog's Cancel closes it; nothing was and nothing is deleted. */
    private fun cancelConfirm() {
        val state = _uiState.value
        if (state.deleting) return // the iOS dialog, not this one, owns the cancel now
        sizingJob?.cancel()
        sizingJob = null
        _uiState.update { it.copy(confirming = false, deleteSize = null) }
    }

    private companion object {
        /** Max ids per `MediaLibrary.items` call (§6.5 step 2, same cap as the prune). */
        const val TILE_META_PAGE_SIZE = 20
    }
}
