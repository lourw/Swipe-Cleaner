@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.swipe.presentation.settings

import app.cash.turbine.expectMostRecentItem
import app.cash.turbine.test
import dev.swipe.domain.model.Decision
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeSensitivityAnalyzer
import dev.swipe.fakes.FakeSettingsStore
import dev.swipe.fakes.FakeSystemActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [SettingsViewModel] (WP-6.1, FR-29–31): the toggles persist through
 * [FakeSettingsStore] and the state mirrors the store back; a failed write
 * leaves the stored value on screen with a save-failed event; "Reset all
 * decisions" is the two-step FR-30 flow — the confirm is the gate, and the
 * clear touches only the KEEP rows. Version and signing expiry are read once
 * from [FakeSystemActions] (FR-31); a null expiry keeps the row hidden.
 */
class SettingsViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-29 toggles persist through the settings store`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val settings = FakeSettingsStore()
            val viewModel = SettingsViewModel(settings, FakeDecisionStore(), FakeSystemActions())

            viewModel.uiState.test {
                // The initial state mirrors the store's production defaults (FR-29).
                val initial = awaitItem()
                assertTrue(initial.hapticsEnabled)
                assertTrue(initial.includeCloudOnly)
                assertTrue(initial.autoplayVideos)

                viewModel.onIntent(SettingsIntent.SetHaptics(false))
                viewModel.onIntent(SettingsIntent.SetIncludeCloudOnly(false))
                viewModel.onIntent(SettingsIntent.SetAutoplayVideos(false))
                advanceUntilIdle()

                // Every setter wrote through to the store…
                assertEquals(
                    listOf("haptics=false", "includeCloudOnly=false", "autoplayVideos=false"),
                    settings.setCalls,
                )
                // …and the state reflects it (mirrored from the store's flows).
                val off = expectMostRecentItem()
                assertFalse(off.hapticsEnabled)
                assertFalse(off.includeCloudOnly)
                assertFalse(off.autoplayVideos)
            }
        }

    @Test
    fun `FR-33 hide sensitive photos defaults on and persists through the settings store`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val settings = FakeSettingsStore()
            val viewModel = SettingsViewModel(settings, FakeDecisionStore(), FakeSystemActions())

            viewModel.uiState.test {
                // FR-33's default is on (the store's default, mirrored at open).
                assertTrue(awaitItem().hideSensitive)

                viewModel.onIntent(SettingsIntent.SetHideSensitive(false))
                advanceUntilIdle()

                // The write went through the store…
                assertEquals(listOf("hideSensitive=false"), settings.setCalls)
                // …and the state mirrors it back.
                assertFalse(expectMostRecentItem().hideSensitive)
            }
        }

    @Test
    fun `FR-16 video sound setting defaults off and persists through the settings store`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val settings = FakeSettingsStore()
            val viewModel = SettingsViewModel(settings, FakeDecisionStore(), FakeSystemActions())

            viewModel.uiState.test {
                // Default off (FR-16): a new Swipe session starts muted.
                assertFalse(awaitItem().playVideoSound)

                viewModel.onIntent(SettingsIntent.SetPlayVideoSound(true))
                advanceUntilIdle()

                // The toggle wrote through to the store…
                assertEquals(listOf("playVideoSound=true"), settings.setCalls)
                // …and the state mirrors it back.
                assertTrue(expectMostRecentItem().playVideoSound)
            }
        }

    @Test
    fun `FR-33 analysis availability is read from the analyzer`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val available =
                SettingsViewModel(
                    FakeSettingsStore(),
                    FakeDecisionStore(),
                    FakeSystemActions(),
                    sensitivity = FakeSensitivityAnalyzer(available = true),
                )
            assertTrue(available.uiState.value.sensitiveAnalysisAvailable)

            // The default parameter (SensitivityAnalyzer.Unavailable) reads false (AC3).
            val defaultVm = SettingsViewModel(FakeSettingsStore(), FakeDecisionStore(), FakeSystemActions())
            assertFalse(defaultVm.uiState.value.sensitiveAnalysisAvailable)
        }

    @Test
    fun `FR-29 failed toggle keeps the stored value and reports it`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val settings = FakeSettingsStore()
            settings.failNextSet = IllegalStateException("defaults unavailable")
            val viewModel = SettingsViewModel(settings, FakeDecisionStore(), FakeSystemActions())

            viewModel.events.test {
                viewModel.onIntent(SettingsIntent.SetHaptics(false))
                assertEquals(SettingsEvent.SaveFailed, awaitItem())
            }
            // The store never changed, so the state still shows it on.
            assertTrue(settings.haptics.value)
            assertTrue(viewModel.uiState.value.hapticsEnabled)
        }

    @Test
    fun `FR-30 reset requires confirmation`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = seededStore()
            val viewModel = SettingsViewModel(FakeSettingsStore(), store, FakeSystemActions())

            viewModel.uiState.test {
                awaitItem()

                // The row tap reads the counts and only then raises the
                // dialog — nothing is cleared (FR-30).
                viewModel.onIntent(SettingsIntent.RequestReset)
                advanceUntilIdle()
                assertTrue(awaitItem().confirmResetVisible)
                assertEquals(0, store.clearKeepsCalls)

                // Cancel closes it, still clearing nothing.
                viewModel.onIntent(SettingsIntent.CancelReset)
                assertFalse(awaitItem().confirmResetVisible)
                advanceUntilIdle()
                assertEquals(0, store.clearKeepsCalls)

                // A Confirm without a preceding Request is ignored too.
                viewModel.onIntent(SettingsIntent.ConfirmReset)
                advanceUntilIdle()
                assertEquals(0, store.clearKeepsCalls)
            }
        }

    @Test
    fun `FR-30 reset clears keeps but not the delete queue`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = seededStore()
            val viewModel = SettingsViewModel(FakeSettingsStore(), store, FakeSystemActions())

            viewModel.events.test {
                viewModel.onIntent(SettingsIntent.RequestReset)
                advanceUntilIdle() // the async count read behind the row tap
                viewModel.onIntent(SettingsIntent.ConfirmReset)
                assertEquals(SettingsEvent.ResetDone(2), awaitItem())
            }
            assertEquals(1, store.clearKeepsCalls)
            // KEEP rows are gone; the pending DELETE and the DELETED history stay.
            assertEquals(listOf("del-1"), store.deleteQueue().first())
            assertEquals(
                setOf(Decision.DELETE, Decision.DELETED),
                store.entries.map { it.decision }.toSet(),
            )
            assertFalse(viewModel.uiState.value.confirmResetVisible)
        }

    @Test
    fun `FR-30 failed reset reports it and leaves the history in place`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = seededStore()
            store.failNextClearKeeps = IllegalStateException("disk full")
            val viewModel = SettingsViewModel(FakeSettingsStore(), store, FakeSystemActions())

            viewModel.events.test {
                viewModel.onIntent(SettingsIntent.RequestReset)
                advanceUntilIdle() // the async count read behind the row tap
                viewModel.onIntent(SettingsIntent.ConfirmReset)
                assertEquals(SettingsEvent.ResetFailed, awaitItem())
            }
            assertTrue(store.entries.any { it.decision == Decision.KEEP })
        }

    @Test
    fun `FR-30 the reset dialog counts the kept items coming back and the queued ones staying`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = seededStore()
            val viewModel = SettingsViewModel(FakeSettingsStore(), store, FakeSystemActions())

            viewModel.onIntent(SettingsIntent.RequestReset)
            advanceUntilIdle()

            // The open dialog reports what the reset will do (FR-30): two kept
            // items come back as undecided, the one queued delete stays queued.
            val state = viewModel.uiState.value
            assertTrue(state.confirmResetVisible)
            assertEquals(2, state.resetKeptCount)
            assertEquals(1, state.resetQueuedCount)
            assertEquals(0, store.clearKeepsCalls)
        }

    @Test
    fun `FR-30 with nothing kept the reset row toasts instead of opening the dialog`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = FakeDecisionStore()
            store.record("del-1", Decision.DELETE, "session-1")
            val viewModel = SettingsViewModel(FakeSettingsStore(), store, FakeSystemActions())

            viewModel.events.test {
                viewModel.onIntent(SettingsIntent.RequestReset)
                // kept == 0: a "nothing to reset" toast, no dialog, no clear.
                assertEquals(SettingsEvent.NothingToReset, awaitItem())
            }
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.confirmResetVisible)
            assertEquals(0, store.clearKeepsCalls)
        }

    @Test
    fun `FR-31 version and signing expiry load from the system actions`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val actions = FakeSystemActions()
            actions.appVersionResult = "1.2 (345)"
            val expiry = Instant.parse("2026-11-08T09:00:00Z")
            actions.signingExpiryResult = expiry

            val viewModel = SettingsViewModel(FakeSettingsStore(), FakeDecisionStore(), actions)
            assertEquals("1.2 (345)", viewModel.uiState.value.appVersion)
            assertEquals(expiry, viewModel.uiState.value.signingExpiry)
        }

    @Test
    fun `FR-31 missing profile hides expiry`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val actions = FakeSystemActions() // signingExpiryResult defaults to null
            actions.appVersionResult = "1.0 (1)"

            val viewModel = SettingsViewModel(FakeSettingsStore(), FakeDecisionStore(), actions)
            assertNull(viewModel.uiState.value.signingExpiry)
        }

    /** KEEP ×2, one pending DELETE, one already-DELETED row (FR-30 fixture). */
    private suspend fun seededStore(): FakeDecisionStore {
        val store = FakeDecisionStore()
        store.record("keep-1", Decision.KEEP, "session-1")
        store.record("keep-2", Decision.KEEP, "session-1")
        store.record("del-1", Decision.DELETE, "session-1")
        store.record("gone-1", Decision.DELETED, "session-1")
        return store
    }
}
