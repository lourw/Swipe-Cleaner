package dev.swipe.presentation.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swipe.data.MediaLibrary
import dev.swipe.data.SettingsStore
import dev.swipe.data.SystemActions
import dev.swipe.domain.model.DeckSource
import dev.swipe.domain.model.LibraryAccess
import dev.swipe.domain.model.MediaTypeFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The permission flow (FR-1–3, §5.8 screen map). Reads the current authorization on
 * start, shows the onboarding screen before the first request, routes FULL and
 * LIMITED to Home and DENIED to the blocking screen, and re-reads whenever the app
 * comes to the foreground (WP-1.7 task 1) or the library changes (WP-3.2), which is
 * what keeps the FR-2 limited-access count honest while Home is on screen.
 */
class PermissionViewModel(
    private val mediaLibrary: MediaLibrary,
    private val settingsStore: SettingsStore,
    private val systemActions: SystemActions,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PermissionUiState())

    /** The one state flow (AGENTS.md ViewModel convention). */
    val uiState: StateFlow<PermissionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { checkAccess() }
        // WP-3.2: limited-library edits (adding or removing items in the system
        // picker) fire `changes()`; re-running [checkAccess] refreshes the FR-2
        // banner count without waiting for a resume. The collect body AWAITS the
        // re-read, so conflation throttles bursts to at most one in-flight scan
        // instead of stacking overlapping authorization + assetIds reads.
        viewModelScope.launch {
            mediaLibrary.changes().collect { checkAccess() }
        }
    }

    /** The one entry point for user and lifecycle actions (AGENTS.md ViewModel convention). */
    fun onIntent(intent: PermissionIntent) {
        when (intent) {
            PermissionIntent.RequestAccess -> requestAccess()
            PermissionIntent.OpenSettings -> systemActions.openSettings()
            PermissionIntent.ManageLimitedAccess -> systemActions.presentLimitedLibraryPicker()
            PermissionIntent.Recheck -> viewModelScope.launch { checkAccess() }
        }
    }

    /** Re-reads the current authorization without prompting (FR-1, FR-3). */
    private suspend fun checkAccess() {
        publish(mediaLibrary.authorization())
    }

    /**
     * Fires the `PHAccessLevel.readWrite` prompt and routes with its outcome (FR-1);
     * the request itself never prompts twice — PhotoKit answers immediately once the
     * user has decided.
     */
    private fun requestAccess() {
        viewModelScope.launch {
            val access = mediaLibrary.requestAuthorization()
            settingsStore.setOnboardingSeen(true)
            publish(access)
        }
    }

    /**
     * Maps [access] to a screen and refreshes the FR-2 count. NOT_DETERMINED always
     * shows the onboarding screen — it is the only screen that can fire the system
     * prompt. FULL or LIMITED shows Home once onboarding was seen (FR-1 returning
     * user), LIMITED also counts the accessible items for the banner (FR-2) with an
     * ids-only fetch (NFR-4), re-run on every `changes()` emission (WP-3.2).
     */
    private suspend fun publish(access: LibraryAccess) {
        val screen =
            when (access) {
                LibraryAccess.DENIED -> {
                    PermissionScreen.DENIED
                }

                LibraryAccess.NOT_DETERMINED -> {
                    PermissionScreen.ONBOARDING
                }

                LibraryAccess.FULL,
                LibraryAccess.LIMITED,
                -> {
                    if (settingsStore.onboardingSeen.value) {
                        PermissionScreen.HOME
                    } else {
                        PermissionScreen.ONBOARDING
                    }
                }
            }
        val limitedCount =
            if (access == LibraryAccess.LIMITED) {
                mediaLibrary.assetIds(DeckSource.All, MediaTypeFilter.ALL).size
            } else {
                null
            }
        _uiState.update { it.copy(startScreen = screen, access = access, limitedCount = limitedCount) }
    }
}
