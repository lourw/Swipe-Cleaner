package dev.swipe.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.DecisionStore
import dev.swipe.data.SettingsStore
import dev.swipe.data.SystemActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Settings screen (FR-29–31): the three toggles ride
 * [SettingsStore]'s flows in both directions, and "Reset all decisions" is the
 * two-step FR-30 flow — [SettingsIntent.RequestReset] only raises the dialog
 * and [SettingsIntent.ConfirmReset] is the only call site of
 * [DecisionStore.clearKeeps], which leaves the delete queue untouched.
 *
 * The store is the single source of truth for the toggle values (NFR-7's
 * discipline in miniature): a setter that throws leaves its `StateFlow`
 * unchanged, so the switch falls back to the stored value on the next emission
 * and the user gets a [SettingsEvent.SaveFailed] toast instead of a lying UI.
 *
 * Version and signing expiry are read once when the screen opens (FR-31):
 * neither changes while the app runs — a new profile means a new build.
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val decisions: DecisionStore,
    systemActions: SystemActions,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            SettingsUiState(
                hapticsEnabled = settings.haptics.value,
                includeCloudOnly = settings.includeCloudOnly.value,
                autoplayVideos = settings.autoplayVideos.value,
                appVersion = systemActions.appVersion(),
                signingExpiry = systemActions.signingExpiry(),
            ),
        )

    /** The one state flow (AGENTS.md ViewModel convention). */
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<SettingsEvent>(Channel.UNLIMITED)

    /** One-shot events (§6.6): the reset and save toasts. */
    val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

    init {
        // Live mirror of the three FR-29 values. The store only changes through
        // this screen today, but the flows keep the state honest regardless.
        viewModelScope.launch {
            combine(
                settings.haptics,
                settings.includeCloudOnly,
                settings.autoplayVideos,
            ) { haptics, cloud, autoplay -> Triple(haptics, cloud, autoplay) }
                .collect { (haptics, cloud, autoplay) ->
                    _uiState.update {
                        it.copy(
                            hapticsEnabled = haptics,
                            includeCloudOnly = cloud,
                            autoplayVideos = autoplay,
                        )
                    }
                }
        }
    }

    /** The one entry point for user actions (AGENTS.md ViewModel convention). */
    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetHaptics -> save { settings.setHaptics(intent.enabled) }
            is SettingsIntent.SetIncludeCloudOnly -> save { settings.setIncludeCloudOnly(intent.enabled) }
            is SettingsIntent.SetAutoplayVideos -> save { settings.setAutoplayVideos(intent.enabled) }
            SettingsIntent.RequestReset -> requestReset()
            SettingsIntent.ConfirmReset -> confirmReset()
            SettingsIntent.CancelReset -> cancelReset()
        }
    }

    /**
     * FR-29: run a synchronous store write; a throw means the value never
     * landed, so the flow mirror keeps the switch truthful and the toast
     * explains it.
     */
    private fun save(write: () -> Unit) {
        try {
            write()
        } catch (error: CancellationException) {
            throw error // ViewModel teardown, not a failed write
        } catch (_: Exception) {
            eventChannel.trySend(SettingsEvent.SaveFailed)
        }
    }

    /** FR-30: the row tap raises the dialog and stops there — nothing is cleared. */
    private fun requestReset() {
        _uiState.update { it.copy(confirmResetVisible = true) }
    }

    /**
     * The dialog's Reset: the only call site of
     * [DecisionStore.clearKeeps] (FR-30). Ignored unless the dialog is up, so
     * the confirm is the gate. Success and failure are reported as events; the
     * KEEP rows' disappearance flows to Home through `DecisionStore.stats()`.
     */
    private fun confirmReset() {
        if (!_uiState.value.confirmResetVisible) return
        _uiState.update { it.copy(confirmResetVisible = false) }
        viewModelScope.launch {
            try {
                decisions.clearKeeps()
            } catch (cancellation: CancellationException) {
                throw cancellation // ViewModel teardown, not a failed reset
            } catch (_: Exception) {
                eventChannel.trySend(SettingsEvent.ResetFailed)
                return@launch
            }
            eventChannel.trySend(SettingsEvent.ResetDone)
        }
    }

    /** FR-30: the dialog's Cancel closes it; the KEEP history is untouched. */
    private fun cancelReset() {
        _uiState.update { it.copy(confirmResetVisible = false) }
    }
}
