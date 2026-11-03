package dev.swipe.presentation.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.DecisionStore
import dev.swipe.data.Haptics
import dev.swipe.domain.usecase.CommitDeletionsUseCase
import dev.swipe.domain.usecase.CommitOutcome
import dev.swipe.domain.usecase.InAppDeleteConfirmation
import dev.swipe.domain.usecase.PruneQueueUseCase
import dev.swipe.domain.usecase.RestoreFromQueueUseCase
import kotlinx.coroutines.CancellationException
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
 * decision first — re-emitted live. On load [PruneQueueUseCase] first drops ids
 * PhotoKit no longer returns, silently. (WP-3.2 re-runs the prune on each
 * `MediaLibrary.changes()` emission.)
 *
 * **Restore** (FR-24): [QueueIntent.Restore] records KEEP via
 * [RestoreFromQueueUseCase]; the queue flow drops the row, and an open viewer for
 * that id closes with it. A failed write emits [QueueEvent.RestoreFailed].
 *
 * **Delete** (FR-25–27, NFR-5, §6.7): [QueueIntent.RequestDelete] only raises the
 * in-app confirm dialog; [QueueIntent.ConfirmDelete] is the *only* place an
 * [InAppDeleteConfirmation] is built — from `items` exactly as the user saw them —
 * and the only path into [CommitDeletionsUseCase]. While the commit runs,
 * [QueueUiState.deleting] blocks re-confirmation, so one batch is one
 * `MediaLibrary.delete` call (FR-25). Outcomes: success fires the
 * `notificationSuccess` haptic and emits [QueueEvent.ShowDeleteSuccess] (the UI
 * navigates; the ViewModel never does); failure emits
 * [QueueEvent.ShowDeleteError] and the queue stays as it was; a cancelled iOS
 * dialog is silent (FR-27).
 */
class QueueViewModel(
    private val commitDeletions: CommitDeletionsUseCase,
    private val pruneQueue: PruneQueueUseCase,
    private val restoreFromQueue: RestoreFromQueueUseCase,
    private val decisions: DecisionStore,
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
            // FR-23: the newest-first order is the store's query, live.
            decisions.deleteQueue().collect { ids ->
                _uiState.update { it.copy(items = ids) }
            }
        }
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

    /** FR-24: open a queued item full-screen; ids the queue no longer has are ignored. */
    private fun open(id: String) {
        val state = _uiState.value
        if (state.deleting || id !in state.items) return
        _uiState.update { it.copy(selected = id) }
    }

    /** Back/Close: drop the viewer and the confirm dialog; data is untouched. */
    private fun close() {
        _uiState.update { it.copy(selected = null, confirming = false) }
    }

    /** FR-24: restore one item; the deleteQueue re-emit removes it from [QueueUiState.items]. */
    private fun restore(id: String) {
        val state = _uiState.value
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
     */
    private fun requestDelete() {
        val state = _uiState.value
        if (state.deleting || state.items.isEmpty()) return
        _uiState.update { it.copy(confirming = true, result = null) }
    }

    /**
     * The dialog's Delete: the sole construction site of [InAppDeleteConfirmation]
     * (§6.7). The token carries `items` exactly as the user saw them;
     * [CommitDeletionsUseCase] intersects it with the current queue and calls
     * PhotoKit once (FR-25).
     */
    private fun confirmDelete() {
        val state = _uiState.value
        // The dialog is the gate (NFR-5), and deleting blocks re-confirmation
        // (FR-25). An empty queue cannot have raised the dialog; a prune while it
        // was up is handled by the use case's empty intersection.
        if (!state.confirming || state.deleting) return
        val confirmation = InAppDeleteConfirmation(state.items)
        _uiState.update { it.copy(confirming = false, deleting = true, result = null) }
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
                    haptics.notificationSuccess()
                    _uiState.update {
                        it.copy(
                            deleting = false,
                            selected = null,
                            result = QueueDeleteResult.Deleted(outcome.deleted),
                        )
                    }
                    eventChannel.trySend(QueueEvent.ShowDeleteSuccess(outcome.deleted))
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
        _uiState.update { it.copy(confirming = false) }
    }
}
