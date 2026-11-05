// The Compose multiplatform UI test API is experimental; opt in once per file.
@file:OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalTestApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.swipe.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.swipe.domain.model.Decision
import dev.swipe.fakes.FakeDecisionStore
import dev.swipe.fakes.FakeSettingsStore
import dev.swipe.fakes.FakeSystemActions
import dev.swipe.presentation.settings.SettingsEvent
import dev.swipe.presentation.settings.SettingsIntent
import dev.swipe.presentation.settings.SettingsUiState
import dev.swipe.presentation.settings.SettingsViewModel
import dev.swipe.ui.screens.swipe.formatCardDate
import dev.swipe.ui.theme.SwipeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Settings screen (WP-6.1, FR-29–31): the three toggle rows render the
 * state (and the whole row is the switch target), the reset row opens the
 * [dev.swipe.ui.components.ConfirmDialog] and only its Reset button sends
 * [SettingsIntent.ConfirmReset], the version row always shows, and the
 * signing-expiry row appears exactly when the state carries an expiry.
 * NFR-5's sibling — the FR-30 gate — runs against the real
 * [SettingsViewModel] over the fakes, so "nothing cleared without the
 * confirm" is asserted on [FakeDecisionStore.clearKeepsCalls].
 */
class SettingsScreenTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `FR-29 toggle rows render the state and toggle through intents`() =
        runComposeUiTest {
            val intents = mutableListOf<SettingsIntent>()
            setContent {
                TestScreen(
                    SettingsUiState(hapticsEnabled = true, includeCloudOnly = false, autoplayVideos = true),
                    onIntent = { intents.add(it) },
                )
            }
            // Titles on screen, switch semantics on the rows (FR-29, NFR-8).
            onNodeWithText("Haptics").assertExists()
            onNodeWithText("Include iCloud-only items").assertExists()
            onNodeWithText("Autoplay videos").assertExists()
            onNodeWithTag(SWITCH_HAPTICS_TEST_TAG).assertIsSelected()
            onNodeWithTag(SWITCH_CLOUD_TEST_TAG).assertIsNotSelected()
            onNodeWithTag(SWITCH_AUTOPLAY_TEST_TAG).assertIsSelected()

            // Tapping a row flips the value it reports (the VM flips the store).
            onNodeWithTag(SWITCH_HAPTICS_TEST_TAG).performClick()
            onNodeWithTag(SWITCH_CLOUD_TEST_TAG).performClick()
            onNodeWithTag(SWITCH_AUTOPLAY_TEST_TAG).performClick()
            waitForIdle()
            assertEquals(
                listOf(
                    SettingsIntent.SetHaptics(false),
                    SettingsIntent.SetIncludeCloudOnly(true),
                    SettingsIntent.SetAutoplayVideos(false),
                ),
                intents,
            )
        }

    @Test
    fun `FR-30 reset row asks for confirmation before anything clears`() =
        runComposeUiTest {
            val f = ScreenFixture()
            setContent { TestScreen(f.viewModel) }
            waitForIdle()

            onNodeWithTag(RESET_ROW_TEST_TAG).performClick()
            waitForIdle()

            // The dialog is up — and it is the only thing that happened (FR-30).
            onNodeWithText("Reset all decisions?").assertExists()
            assertEquals(0, f.store.clearKeepsCalls)
            assertTrue(f.store.entries.any { it.decision == Decision.KEEP })

            // Cancel closes it; the KEEP history is untouched.
            onNodeWithContentDescription("Cancel").performClick()
            waitForIdle()
            onNodeWithText("Reset all decisions?").assertDoesNotExist()
            assertEquals(0, f.store.clearKeepsCalls)
        }

    @Test
    fun `FR-30 dialog Reset clears keeps and keeps the queue`() =
        runComposeUiTest {
            val f = ScreenFixture()
            setContent { TestScreen(f.viewModel) }
            waitForIdle()

            onNodeWithTag(RESET_ROW_TEST_TAG).performClick()
            waitForIdle()
            onNodeWithContentDescription("Reset").performClick()
            waitForIdle()

            // KEEP rows are gone; the pending DELETE survives the reset (FR-30).
            assertEquals(1, f.store.clearKeepsCalls)
            assertEquals(listOf("del-1"), f.store.deleteQueue().first())
            onNodeWithText("Keep history cleared").assertExists()
        }

    @Test
    fun `FR-31 version row always shows and the expiry row follows the state`() =
        runComposeUiTest {
            val expiry = Instant.parse("2026-11-08T09:00:00Z")
            setContent {
                TestScreen(
                    SettingsUiState(appVersion = "1.2 (345)", signingExpiry = expiry),
                )
            }
            onNodeWithTag(VERSION_ROW_TEST_TAG).assertExists()
            onNodeWithText("1.2 (345)").assertExists()
            onNodeWithTag(EXPIRY_ROW_TEST_TAG).assertExists()
            onNodeWithText(formatCardDate(expiry)).assertExists()
        }

    @Test
    fun `FR-31 missing profile hides expiry`() =
        runComposeUiTest {
            setContent { TestScreen(SettingsUiState(appVersion = "1.0 (1)")) }
            onNodeWithTag(EXPIRY_ROW_TEST_TAG).assertDoesNotExist()
            onNodeWithTag(VERSION_ROW_TEST_TAG).assertExists()
        }

    @Test
    fun `FR-29 save failure rides a toast`() =
        runComposeUiTest {
            setContent { TestScreen(SettingsUiState(), events = flowOf(SettingsEvent.SaveFailed)) }
            waitForIdle()
            onNodeWithContentDescription("Couldn't save setting").assertExists()
        }

    /** The screen under the theme, driven by a fixed [state] with inert defaults. */
    @Composable
    private fun TestScreen(
        state: SettingsUiState,
        events: Flow<SettingsEvent> = flowOf(),
        onIntent: (SettingsIntent) -> Unit = {},
    ) {
        SwipeTheme {
            SettingsScreen(
                state = state,
                events = events,
                onIntent = onIntent,
                onBack = {},
            )
        }
    }

    /** Collects the fixture VM's state, like the production container does. */
    @Composable
    private fun TestScreen(viewModel: SettingsViewModel) {
        SwipeTheme {
            SettingsScreen(
                state = viewModel.uiState.collectAsState().value,
                events = viewModel.events,
                onIntent = viewModel::onIntent,
                onBack = {},
            )
        }
    }

    /**
     * The real [SettingsViewModel] over the fakes with two KEEP rows and one
     * pending DELETE (FR-30 fixture); main is the unconfined test dispatcher
     * so the ViewModel scope runs inline on the UI test thread.
     */
    private class ScreenFixture {
        val store = FakeDecisionStore()
        val settings = FakeSettingsStore()
        val viewModel: SettingsViewModel

        init {
            runBlocking {
                store.record("keep-1", Decision.KEEP, "session-1")
                store.record("keep-2", Decision.KEEP, "session-1")
                store.record("del-1", Decision.DELETE, "session-1")
            }
            Dispatchers.setMain(UnconfinedTestDispatcher())
            viewModel = SettingsViewModel(settings, store, FakeSystemActions())
        }
    }
}
